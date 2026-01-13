package com.ratelimiter.api

import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger

import core.{CachedResponse, IdempotencyResult, IdempotencyStore}
import events.{EventPublisher, RateLimitEvent}
import cats.effect.{Async, Clock, Spawn}
import cats.effect.syntax.spawn.*
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.syntax.*

// --- Request/Response DTOs ---

case class IdempotencyCheckRequest(idempotencyKey: String, ttl: Option[Long])

case class IdempotencyCheckResponse(
    status: String, // "new" or "duplicate"
    idempotencyKey: String,
    originalResponse: Option[CachedResponse],
)

case class IdempotencyStoreRequest(
    idempotencyKey: String,
    response: CachedResponse,
)

/** HTTP API for idempotency operations.
  *
  * Endpoints: POST /v1/idempotency/check - Check if operation is new or
  * duplicate POST /v1/idempotency/store - Store response for completed
  * operation
  */
class IdempotencyApi[F[_]: Async: Spawn: Logger](
    store: IdempotencyStore[F],
    eventPublisher: EventPublisher[F],
    defaultTtlSeconds: Long = 86400, // 24 hours
) extends Http4sDsl[F]:

  private val logger = Logger[F]

  given EntityDecoder[F, IdempotencyCheckRequest] =
    jsonOf[F, IdempotencyCheckRequest]
  given EntityDecoder[F, IdempotencyStoreRequest] =
    jsonOf[F, IdempotencyStoreRequest]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case req @ POST -> Root / "v1" / "idempotency" / "check" =>
      for
        checkReq <- req.as[IdempotencyCheckRequest]
        ttl = checkReq.ttl.getOrElse(defaultTtlSeconds)

        // Extract API key for events
        apiKey = req.headers.get(ci"Authorization")
          .map(_.head.value.stripPrefix("Bearer ").trim).getOrElse("anonymous")

        _ <- logger.debug(s"Idempotency check: key=${checkReq.idempotencyKey}")
        result <- store.checkOrCreate(checkReq.idempotencyKey, ttl)

        // Publish event for duplicates
        _ <- result match
          case IdempotencyResult.Duplicate(key, _) =>
            for
              nowMs <- Clock[F].realTime.map(_.toMillis)
              event = RateLimitEvent.IdempotencyHit(nowMs, apiKey, key)
              _ <- eventPublisher.publish(event).start
            yield ()
          case _ => Async[F].unit

        response <- result match
          case IdempotencyResult.New(key) => Ok(
              IdempotencyCheckResponse(
                status = "new",
                idempotencyKey = key,
                originalResponse = None,
              ).asJson,
            )

          case IdempotencyResult.Duplicate(key, cachedResponse) => Ok(
              IdempotencyCheckResponse(
                status = "duplicate",
                idempotencyKey = key,
                originalResponse = cachedResponse,
              ).asJson,
            )
      yield response

    case req @ POST -> Root / "v1" / "idempotency" / "store" =>
      for
        storeReq <- req.as[IdempotencyStoreRequest]
        _ <- logger.debug(s"Storing response for key=${storeReq.idempotencyKey}")
        _ <- store.storeResponse(storeReq.idempotencyKey, storeReq.response)
        response <- NoContent()
      yield response
  }
