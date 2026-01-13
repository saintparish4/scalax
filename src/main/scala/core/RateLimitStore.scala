package core

import scala.concurrent.duration.FiniteDuration

import cats.effect.Temporal

// Result of a rate limit check
sealed trait RateLimitDecision:
  def allowed: Boolean

object RateLimitDecision:
  // Request is allowed within the rate limit
  case class Allowed(tokensRemaining: Int, resetAt: java.time.Instant)
      extends RateLimitDecision:
    val allowed: Boolean = true

  // Request is rejected due to rate limit exceeded
  case class Rejected(retryAfterSeconds: Int, resetAt: java.time.Instant)
      extends RateLimitDecision:
    val allowed: Boolean = false

// Configuration for a rate limit profile
case class RateLimitProfile(
    capacity: Int,
    refillRatePerSecond: Double,
    ttlSeconds: Long,
):
  require(capacity > 0, "Capacity must be positive")
  require(refillRatePerSecond > 0, "Refill rate must be positive")
  require(ttlSeconds > 0, "TTL must be positive")

/** Trait for rate limt storage backends
  *
  * Implementations must provide atomic check-and-decrement semantics to prevent
  * race conditions under concurrent load
  *
  * @tparam F
  *   the effect type
  */
trait RateLimitStore[F[_]]:
  /** Check if a request is allowed and atomically consume tokens if so
    *
    * @param key
    *   unique identifer for the rate limit bucket
    * @param cost
    *   number of tokens to consume (default 1)
    * @param profile
    *   rate limit configuration to applu
    * @return
    *   decision indicating whether request is allowed
    */
  def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision]

  /** Get current token count for a key without consuming Used for status
    * endpoints
    */
  def getStatus(
      key: String,
      profile: RateLimitProfile,
  ): F[Option[TokenBucketState]]

  // Health check for the storage backend
  def healthCheck: F[Boolean]

// Internal state of a token bucket
case class TokenBucketState(
    tokens: Double,
    lastRefillEpochMs: Long,
    version: Long,
):
  def tokensInt: Int = tokens.toInt
