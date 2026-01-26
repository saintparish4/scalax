package events

import cats.effect.* 
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.* 
import io.circe.syntax.*
import java.time.Instant 

/**
 * Rate limit events for analytics and monitoring.
 */
sealed trait RateLimitEvent:
  def timestamp: Instant
  def eventType: String
  def partitionKey: String

object RateLimitEvent:
  /**
   * Request was allowed within rate limit.
   */
  case class Allowed(
      timestamp: Instant,
      apiKey: String,
      clientId: String,
      endpoint: String,
      tokensRemaining: Int,
      cost: Int,
      tier: String
  ) extends RateLimitEvent:
    val eventType = "rate_limit_allowed"
    val partitionKey = apiKey

  /**
   * Request was rejected due to rate limit exceeded.
   */
  case class Rejected(
      timestamp: Instant,
      apiKey: String,
      clientId: String,
      endpoint: String,
      retryAfterSeconds: Int,
      reason: String,
      tier: String
  ) extends RateLimitEvent:
    val eventType = "rate_limit_rejected"
    val partitionKey = apiKey

  /**
   * Idempotency key hit (duplicate detected).
   */
  case class IdempotencyHit(
      timestamp: Instant,
      idempotencyKey: String,
      clientId: String,
      originalRequestTime: Instant
  ) extends RateLimitEvent:
    val eventType = "idempotency_hit"
    val partitionKey = clientId

  /**
   * New idempotency key registered.
   */
  case class IdempotencyNew(
      timestamp: Instant,
      idempotencyKey: String,
      clientId: String,
      ttlSeconds: Long
  ) extends RateLimitEvent:
    val eventType = "idempotency_new"
    val partitionKey = clientId

  /**
   * Circuit breaker state change.
   */
  case class CircuitBreakerStateChange(
      timestamp: Instant,
      name: String,
      previousState: String,
      newState: String,
      failureCount: Int
  ) extends RateLimitEvent:
    val eventType = "circuit_breaker_state_change"
    val partitionKey = name

  /**
   * Degraded mode activated/deactivated.
   */
  case class DegradedModeChange(
      timestamp: Instant,
      service: String,
      degraded: Boolean,
      reason: String
  ) extends RateLimitEvent:
    val eventType = "degraded_mode_change"
    val partitionKey = service

  // JSON encoder for events
  given Encoder[RateLimitEvent] = Encoder.instance {
    case e: Allowed => e.asJson.deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: Rejected => e.asJson.deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: IdempotencyHit => e.asJson.deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: IdempotencyNew => e.asJson.deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: CircuitBreakerStateChange => e.asJson.deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
    case e: DegradedModeChange => e.asJson.deepMerge(Json.obj("event_type" -> Json.fromString(e.eventType)))
  }

/**
 * Event publisher trait for publishing rate limit events.
 */
trait EventPublisher[F[_]]:
  /**
   * Publish a single event.
   * Events are published asynchronously - failures should not fail the main request.
   */
  def publish(event: RateLimitEvent): F[Unit]

  /**
   * Publish multiple events in a batch for efficiency.
   */
  def publishBatch(events: List[RateLimitEvent]): F[Unit]

  /**
   * Flush any buffered events immediately.
   */
  def flush: F[Unit]

  /**
   * Health check for the publisher.
   */
  def healthCheck: F[Boolean]

object EventPublisher:
  /**
   * Create a no-op publisher for testing or when events are disabled.
   */
  def noop[F[_]: Sync]: EventPublisher[F] =
    new EventPublisher[F]:
      override def publish(event: RateLimitEvent): F[Unit] = Sync[F].unit
      override def publishBatch(events: List[RateLimitEvent]): F[Unit] = Sync[F].unit
      override def flush: F[Unit] = Sync[F].unit
      override def healthCheck: F[Boolean] = Sync[F].pure(true)

  /**
   * Create a logging publisher for development.
   */
  def logging[F[_]: Sync](using logger: org.typelevel.log4cats.Logger[F]): EventPublisher[F] =
    new EventPublisher[F]:
      override def publish(event: RateLimitEvent): F[Unit] =
        logger.info(s"EVENT: ${event.eventType} - ${event.asJson.noSpaces}")
      
      override def publishBatch(events: List[RateLimitEvent]): F[Unit] =
        events.traverse_(publish)
      
      override def flush: F[Unit] = Sync[F].unit
      
      override def healthCheck: F[Boolean] = Sync[F].pure(true)

  /**
   * Create a buffered publisher that batches events.
   */
  def buffered[F[_]: Temporal](
      underlying: EventPublisher[F],
      batchSize: Int = 100,
      flushInterval: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.Duration(1, "second")
  )(using logger: org.typelevel.log4cats.Logger[F]): Resource[F, EventPublisher[F]] =
    for
      bufferRef <- Resource.eval(Ref.of[F, List[RateLimitEvent]](List.empty))
      lastFlushRef <- Resource.eval(Ref.of[F, Long](System.currentTimeMillis()))
      
      // Background flush fiber
      _ <- Resource.make(
        flushLoop(bufferRef, lastFlushRef, underlying, flushInterval, logger).start
      )(_.cancel)
    yield new EventPublisher[F]:
      override def publish(event: RateLimitEvent): F[Unit] =
        bufferRef.modify { buffer =>
          val newBuffer = event :: buffer
          if newBuffer.size >= batchSize then
            (List.empty, Some(newBuffer.reverse))
          else
            (newBuffer, None)
        }.flatMap {
          case Some(batch) => underlying.publishBatch(batch)
          case None => Temporal[F].unit
        }
      
      override def publishBatch(events: List[RateLimitEvent]): F[Unit] =
        events.traverse_(publish)
      
      override def flush: F[Unit] =
        bufferRef.getAndSet(List.empty).flatMap { buffer =>
          if buffer.nonEmpty then underlying.publishBatch(buffer.reverse)
          else Temporal[F].unit
        }
      
      override def healthCheck: F[Boolean] =
        underlying.healthCheck

  private def flushLoop[F[_]: Temporal](
      bufferRef: Ref[F, List[RateLimitEvent]],
      lastFlushRef: Ref[F, Long],
      underlying: EventPublisher[F],
      interval: scala.concurrent.duration.FiniteDuration,
      logger: org.typelevel.log4cats.Logger[F]
  ): F[Nothing] =
    (
      Temporal[F].sleep(interval) *>
      bufferRef.getAndSet(List.empty).flatMap { buffer =>
        if buffer.nonEmpty then
          underlying.publishBatch(buffer.reverse).handleErrorWith { error =>
            logger.error(error)("Failed to flush event buffer")
          }
        else
          Temporal[F].unit
      } *>
      lastFlushRef.set(System.currentTimeMillis())
    ).foreverM
