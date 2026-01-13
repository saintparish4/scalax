package storage

import core.{CachedResponse, IdempotencyResult, IdempotencyStore}

import cats.effect.{Ref, Sync}
import cats.syntax.all.*

/** In-memory idempotency store for testing purposes.
  */
class InMemoryIdempotencyStore[F[_]: Sync](
    stateRef: Ref[F, Map[String, Option[CachedResponse]]],
) extends IdempotencyStore[F]:

  override def checkOrCreate(
      key: String,
      ttlSeconds: Long,
  ): F[IdempotencyResult] = stateRef.modify(stateMap =>
    stateMap.get(key) match
      case Some(cachedResponse) =>
        // Key exists - return duplicate
        (stateMap, IdempotencyResult.Duplicate(key, cachedResponse))
      case None =>
        // Key doesn't exist - create with pending status (None response)
        val newMap = stateMap.updated(key, None)
        (newMap, IdempotencyResult.New(key)),
  )

  override def storeResponse(key: String, response: CachedResponse): F[Unit] =
    stateRef.update(_.updated(key, Some(response)))

  override def healthCheck: F[Boolean] = Sync[F].pure(true)

object InMemoryIdempotencyStore:
  def create[F[_]: Sync]: F[InMemoryIdempotencyStore[F]] = Ref
    .of[F, Map[String, Option[CachedResponse]]](Map.empty)
    .map(new InMemoryIdempotencyStore[F](_))
