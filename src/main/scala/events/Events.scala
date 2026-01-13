package events

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Encoder, Json}

// Base trait for all rate limit events published to Kinesis
sealed trait RateLimitEvent:
  def timestamp: Long
  def eventType: String
  def partitionKey: String

object RateLimitEvent:
  // A request was allowed within rate limits
  case class Allowed(
      timestamp: Long,
      apiKey: String,
      requestKey: String,
      endpoint: Option[String],
      tokensRemaining: Int,
      cost: Int,
  ) extends RateLimitEvent:
    val eventType: String = "rate_limit_allowed"
    val partitionKey: String = apiKey

  // A request was rejected due to rate limit
  case class Rejected(
      timestamp: Long,
      apiKey: String,
      requestKey: String,
      endpoint: Option[String],
      retryAfterSeconds: Int,
      reason: String,
  ) extends RateLimitEvent:
    val eventType: String = "rate_limit_rejected"
    val partitionKey: String = apiKey

  // An idempotency key was hit (duplicate request)
  case class IdempotencyHit(
      timestamp: Long,
      apiKey: String,
      idempotencyKey: String,
  ) extends RateLimitEvent:
    val eventType: String = "idempotency_hit"
    val partitionKey: String = apiKey

  // Circe encoders for JSON serialization
  given Encoder[Allowed] = deriveEncoder[Allowed]
  given Encoder[Rejected] = deriveEncoder[Rejected]
  given Encoder[IdempotencyHit] = deriveEncoder[IdempotencyHit]

  given Encoder[RateLimitEvent] = Encoder.instance {
    case e: Allowed => e.asJson
    case e: Rejected => e.asJson
    case e: IdempotencyHit => e.asJson
  }
