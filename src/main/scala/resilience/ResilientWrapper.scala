package resilience 

import cats.effect.*
import cats.syntax.all.*
import com.ratelimiter.config.ResilienceConfig
import com.ratelimiter.core.{RateLimitDecision, RateLimitProfile, RateLimitStore}
import com.ratelimiter.events.{EventPublisher, RateLimitEvent}
import com.ratelimiter.metrics.MetricsPublisher
import org.typelevel.log4cats.Logger
import scala.concurrent.duration.

/**
 * Resilient wrapper that applies production hardening patterns to the rate limit store.
 *
 * Provides:
 * - Circuit breaker protection against cascading failures
 * - Retry with exponential backoff for transient failures
 * - Local caching for reduced latency and backend protection
 * - Bulkhead isolation to prevent resource exhaustion
 * - Graceful degradation when dependencies fail
 * - Comprehensive metrics and logging
 */
object ResilientRateLimitStore:
  
  /**
   * Wrap a rate limit store with production resilience patterns.
   */
  def apply[F[_]: Temporal: Logger](
      underlying: RateLimitStore[F],
      config: ResilienceConfig,
      metrics: MetricsPublisher[F],
      eventPublisher: EventPublisher[F],
      degradationMode: GracefulDegradation.DegradationMode = GracefulDegradation.DegradationMode.AllowAll
  ): Resource[F, RateLimitStore[F]] =
    for
      // Create circuit breaker
      circuitBreaker <- Resource.eval(
        if config.circuitBreaker.enabled then
          CircuitBreaker[F](
            "dynamodb-ratelimit",
            CircuitBreakerConfig(
              maxFailures = config.circuitBreaker.dynamodb.maxFailures,
              resetTimeout = config.circuitBreaker.dynamodb.resetTimeout,
              halfOpenMaxCalls = config.circuitBreaker.dynamodb.halfOpenMaxCalls
            )
          ).map(Some(_))
        else
          Temporal[F].pure(None)
      )
      
      // Create bulkhead
      bulkhead <- Resource.eval(
        if config.bulkhead.enabled then
          Bulkhead[F](
            "ratelimit-operations",
            BulkheadConfig(
              maxConcurrent = config.bulkhead.maxConcurrent,
              maxWait = config.bulkhead.maxWait
            )
          ).map(Some(_))
        else
          Temporal[F].pure(None)
      )
      
      // Create health tracker
      healthTracker <- Resource.eval(
        HealthAwareService.tracker[F]()
      )
      
    yield new RateLimitStore[F]:
      private val logger = Logger[F]
      
      private val retryPolicy = RetryPolicy(
        maxRetries = config.retry.dynamodb.maxRetries,
        baseDelay = config.retry.dynamodb.baseDelay,
        maxDelay = config.retry.dynamodb.maxDelay,
        multiplier = config.retry.dynamodb.multiplier,
        retryOn = isRetryable
      )
      
      override def checkAndConsume(
          key: String,
          cost: Int,
          profile: RateLimitProfile
      ): F[RateLimitDecision] =
        val operation = metrics.timed("RateLimitCheckLatency", Map("operation" -> "checkAndConsume")) {
          underlying.checkAndConsume(key, cost, profile)
        }
        
        // Apply patterns in order: bulkhead -> circuit breaker -> retry -> timeout
        val protected = applyPatterns(operation, "checkAndConsume")
        
        // Handle failures with graceful degradation
        protected
          .flatTap(decision => recordDecision(key, decision))
          .flatTap(_ => healthTracker.recordSuccess)
          .handleErrorWith { error =>
            healthTracker.recordFailure(error) *>
            handleDegradation(key, error)
          }
      
      override def getStatus(
          key: String,
          profile: RateLimitProfile
      ): F[Option[RateLimitDecision.Allowed]] =
        val operation = metrics.timed("RateLimitStatusLatency", Map("operation" -> "getStatus")) {
          underlying.getStatus(key, profile)
        }
        
        applyPatterns(operation, "getStatus")
          .flatTap(_ => healthTracker.recordSuccess)
          .handleErrorWith { error =>
            healthTracker.recordFailure(error) *>
            logger.warn(s"Failed to get status for $key, returning None: ${error.getMessage}") *>
            Temporal[F].pure(None)
          }
      
      override def healthCheck: F[Boolean] =
        healthTracker.isHealthy.flatMap { healthy =>
          if healthy then
            Temporal[F].timeout(underlying.healthCheck, config.timeout.healthCheck)
              .handleError(_ => false)
          else
            Temporal[F].pure(false)
        }
      
      private def applyPatterns[A](operation: F[A], name: String): F[A] =
        // Start with the base operation
        var protected = operation
        
        // Apply timeout
        protected = Temporal[F].timeout(protected, config.timeout.rateLimitCheck)
          .adaptError { case _: java.util.concurrent.TimeoutException =>
            new RuntimeException(s"Operation $name timed out after ${config.timeout.rateLimitCheck}")
          }
        
        // Apply retry
        protected = Retry.withPolicy(retryPolicy, name)(protected)
        
        // Apply circuit breaker
        circuitBreaker.foreach { cb =>
          protected = cb.protect(protected).flatTap { _ =>
            cb.metrics.flatMap { m =>
              metrics.recordCircuitBreakerState("dynamodb-ratelimit", m.state.toString, m.failureCount)
            }
          }
        }
        
        // Apply bulkhead
        bulkhead.foreach { bh =>
          protected = bh.execute(protected)
        }
        
        protected
      
      private def handleDegradation(
          key: String,
          error: Throwable
      ): F[RateLimitDecision] =
        error match
          case _: CircuitBreakerOpen =>
            logger.warn(s"Circuit breaker open for key $key, applying degradation mode") *>
            metrics.increment("RateLimitDegraded", Map("reason" -> "circuit_breaker")) *>
            GracefulDegradation.degradedDecision(degradationMode, key)
          
          case _: BulkheadRejected =>
            logger.warn(s"Bulkhead rejected for key $key, applying degradation mode") *>
            metrics.increment("RateLimitDegraded", Map("reason" -> "bulkhead")) *>
            GracefulDegradation.degradedDecision(degradationMode, key)
          
          case _ =>
            logger.error(error)(s"Unexpected error for key $key, applying degradation mode") *>
            metrics.increment("RateLimitDegraded", Map("reason" -> "error")) *>
            GracefulDegradation.degradedDecision(degradationMode, key)
      
      private def recordDecision(key: String, decision: RateLimitDecision): F[Unit] =
        decision match
          case RateLimitDecision.Allowed(tokens, _) =>
            metrics.increment("RateLimitAllowed") *>
            metrics.gauge("TokensRemaining", tokens.toDouble, Map("key_prefix" -> keyPrefix(key)))
          case RateLimitDecision.Rejected(retryAfter, _) =>
            metrics.increment("RateLimitRejected") *>
            metrics.gauge("RetryAfterSeconds", retryAfter.toDouble, Map("key_prefix" -> keyPrefix(key)))
      
      private def keyPrefix(key: String): String =
        key.split(":").headOption.getOrElse("unknown")
      
      private def isRetryable(error: Throwable): Boolean =
        error match
          case _: software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException => true
          case _: software.amazon.awssdk.core.exception.SdkServiceException => true
          case _: java.util.concurrent.TimeoutException => true
          case _: java.io.IOException => true
          case _ => false

/**
 * Builder for creating resilient stores with fluent API.
 */
class ResilientStoreBuilder[F[_]: Temporal: Logger](
    underlying: RateLimitStore[F]
):
  private var _config: Option[ResilienceConfig] = None
  private var _metrics: Option[MetricsPublisher[F]] = None
  private var _events: Option[EventPublisher[F]] = None
  private var _degradationMode: GracefulDegradation.DegradationMode = 
    GracefulDegradation.DegradationMode.AllowAll
  
  def withConfig(config: ResilienceConfig): ResilientStoreBuilder[F] =
    _config = Some(config)
    this
  
  def withMetrics(metrics: MetricsPublisher[F]): ResilientStoreBuilder[F] =
    _metrics = Some(metrics)
    this
  
  def withEvents(events: EventPublisher[F]): ResilientStoreBuilder[F] =
    _events = Some(events)
    this
  
  def withDegradationMode(mode: GracefulDegradation.DegradationMode): ResilientStoreBuilder[F] =
    _degradationMode = mode
    this
  
  def build: Resource[F, RateLimitStore[F]] =
    val config = _config.getOrElse(defaultConfig)
    
    for
      metrics <- _metrics match
        case Some(m) => Resource.pure[F, MetricsPublisher[F]](m)
        case None => Resource.eval(MetricsPublisher.noop[F])
      
      events <- _events match
        case Some(e) => Resource.pure[F, EventPublisher[F]](e)
        case None => Resource.pure[F, EventPublisher[F]](EventPublisher.noop[F])
      
      store <- ResilientRateLimitStore(underlying, config, metrics, events, _degradationMode)
    yield store
  
  private def defaultConfig: ResilienceConfig =
    import com.ratelimiter.config.*
    ResilienceConfig(
      circuitBreaker = CircuitBreakerSettings(
        enabled = true,
        dynamodb = CircuitBreakerConfig(),
        kinesis = CircuitBreakerConfig()
      ),
      retry = RetrySettings(
        dynamodb = RetryConfig(),
        kinesis = RetryConfig()
      ),
      bulkhead = BulkheadSettings(),
      timeout = TimeoutSettings()
    )

object ResilientStoreBuilder:
  def apply[F[_]: Temporal: Logger](store: RateLimitStore[F]): ResilientStoreBuilder[F] =
    new ResilientStoreBuilder[F](store)