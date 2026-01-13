package com.ratelimiter.api

import java.time.Instant

import scala.concurrent.duration.*

import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Retry-After`
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger

import config.RateLimitConfig
import core.{RateLimitDecision, RateLimitProfile, RateLimitStore}
import events.{EventPublisher, RateLimitEvent}
import cats.effect.{Async, Clock, Spawn}
import cats.effect.syntax.spawn.*
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.syntax.*

// --- Request/Response DTOs ---

case class RateLimitCheckRequest(
    key: String,
    cost: Option[Int],
    algorithm: Option[String],
)

case class RateLimitCheckResponse(
    allowed: Boolean,
    tokensRemaining: Option[Int],
    retryAfter: Option[Int],
    limit: Int,
    resetAt: String,
    message: Option[String],
)

case class RateLimitStatusResponse(
    key: String,
    tokensRemaining: Int,
    limit: Int,
    resetAt: String,
)

/** HTTP API for rate limiting operations.
  *
  * Endpoints: POST /v1/ratelimit/check - Check and consume rate limit GET
  * /v1/ratelimit/status/:key - Get current status for a key
  */
class RateLimitApi[F[_]: Async: Spawn: Logger](
    store: RateLimitStore[F],
    eventPublisher: EventPublisher[F],
    config: RateLimitConfig,
) extends Http4sDsl[F]:

  private val logger = Logger[F]

  private val defaultProfile = RateLimitProfile(
    capacity = config.defaultCapacity,
    refillRatePerSecond = config.defaultRefillRatePerSecond,
    ttlSeconds = config.defaultTtlSeconds,
  )

  given EntityDecoder[F, RateLimitCheckRequest] =
    jsonOf[F, RateLimitCheckRequest]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case req @ POST -> Root / "v1" / "ratelimit" / "check" =>
      for
        // Parse request
        checkReq <- req.as[RateLimitCheckRequest]
        cost = checkReq.cost.getOrElse(1)

        // Get current timestamp for event
        nowMs <- Clock[F].realTime.map(_.toMillis)

        // Extract API key from header (for events) - default to "anonymous"
        apiKey = req.headers.get(ci"Authorization")
          .map(_.head.value.stripPrefix("Bearer ").trim).getOrElse("anonymous")

        // Perform rate limit check
        _ <- logger.debug(s"Rate limit check: key=${checkReq.key}, cost=$cost")
        decision <- store.checkAndConsume(checkReq.key, cost, defaultProfile)

        // Publish event (fire and forget)
        event = decision match
          case RateLimitDecision.Allowed(remaining, _) => RateLimitEvent.Allowed(
              timestamp = nowMs,
              apiKey = apiKey,
              requestKey = checkReq.key,
              endpoint = None,
              tokensRemaining = remaining,
              cost = cost,
            )
          case RateLimitDecision.Rejected(retryAfter, _) => RateLimitEvent
              .Rejected(
                timestamp = nowMs,
                apiKey = apiKey,
                requestKey = checkReq.key,
                endpoint = None,
                retryAfterSeconds = retryAfter,
                reason = "rate_limit_exceeded",
              )
        _ <- eventPublisher.publish(event).start // Fire and forget

        // Build response
        response <- decision match
          case RateLimitDecision.Allowed(remaining, resetAt) => Ok(
              RateLimitCheckResponse(
                allowed = true,
                tokensRemaining = Some(remaining),
                retryAfter = None,
                limit = defaultProfile.capacity,
                resetAt = resetAt.toString,
                message = None,
              ).asJson,
            )

          case RateLimitDecision.Rejected(retryAfter, resetAt) =>
            TooManyRequests(
              RateLimitCheckResponse(
                allowed = false,
                tokensRemaining = Some(0),
                retryAfter = Some(retryAfter),
                limit = defaultProfile.capacity,
                resetAt = resetAt.toString,
                message = Some("Rate limit exceeded"),
              ).asJson,
            ).map(_.putHeaders(
              `Retry-After`.unsafeFromDuration(retryAfter.seconds),
            ))
      yield response

    case GET -> Root / "v1" / "ratelimit" / "status" / key =>
      for
        nowMs <- Clock[F].realTime.map(_.toMillis)
        status <- store.getStatus(key, defaultProfile)

        response <- status match
          case Some(state) =>
            val resetAt = Instant.ofEpochMilli(nowMs).plusSeconds(
              ((defaultProfile.capacity - state.tokens) /
                defaultProfile.refillRatePerSecond).ceil.toLong,
            )
            Ok(
              RateLimitStatusResponse(
                key = key,
                tokensRemaining = state.tokensInt,
                limit = defaultProfile.capacity,
                resetAt = resetAt.toString,
              ).asJson,
            )

          case None =>
            // No state means full capacity (never seen this key)
            val resetAt = Instant.ofEpochMilli(nowMs)
            Ok(
              RateLimitStatusResponse(
                key = key,
                tokensRemaining = defaultProfile.capacity,
                limit = defaultProfile.capacity,
                resetAt = resetAt.toString,
              ).asJson,
            )
      yield response
  }
