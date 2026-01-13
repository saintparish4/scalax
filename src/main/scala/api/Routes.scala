package com.ratelimiter.api

import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.{Logger as Http4sLogger, RequestId}
import org.typelevel.log4cats.Logger

import config.{AppConfig, RateLimitConfig}
import core.{IdempotencyStore, RateLimitStore}
import events.EventPublisher
import cats.effect.Async
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.syntax.*

// --- Health check responses ---

case class HealthResponse(status: String, version: String)

case class ReadyResponse(status: String, checks: Map[String, Boolean])

/** Aggregates all API routes and applies middleware.
  */
class Routes[F[_]: Async: Logger](
    rateLimitStore: RateLimitStore[F],
    idempotencyStore: IdempotencyStore[F],
    eventPublisher: EventPublisher[F],
    config: AppConfig,
) extends Http4sDsl[F]:

  private val logger = Logger[F]

  private val rateLimitApi =
    new RateLimitApi[F](rateLimitStore, eventPublisher, config.rateLimit)

  private val idempotencyApi = new IdempotencyApi[F](
    idempotencyStore,
    eventPublisher,
    config.rateLimit.defaultTtlSeconds,
  )

  private val healthRoutes: HttpRoutes[F] = HttpRoutes.of[F] {

    // Liveness probe - always returns 200 if service is running
    case GET -> Root / "health" =>
      Ok(HealthResponse(status = "healthy", version = "0.1.0").asJson)

    // Readiness probe - checks dependencies
    case GET -> Root / "ready" =>
      for
        dynamoOk <- rateLimitStore.healthCheck
        kinesisOk <- eventPublisher.healthCheck
        idempotencyOk <- idempotencyStore.healthCheck

        checks = Map(
          "dynamodb_ratelimit" -> dynamoOk,
          "dynamodb_idempotency" -> idempotencyOk,
          "kinesis" -> kinesisOk,
        )

        allHealthy = checks.values.forall(identity)

        response <-
          if allHealthy then
            Ok(ReadyResponse(status = "ready", checks = checks).asJson)
          else
            ServiceUnavailable(
              ReadyResponse(status = "not ready", checks = checks).asJson,
            )
      yield response
  }

  // Combine all routes
  private val allRoutes: HttpRoutes[F] = healthRoutes <+>
    rateLimitApi.routes <+> idempotencyApi.routes

  // Apply middleware
  val httpApp: HttpApp[F] =
    // Log requests/responses in debug mode
    Http4sLogger.httpApp(
      logHeaders = true,
      logBody = false,
      logAction = Some((msg: String) => logger.debug(msg)),
    )(allRoutes.orNotFound)
