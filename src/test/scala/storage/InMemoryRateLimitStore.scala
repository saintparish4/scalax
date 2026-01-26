package storage

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.all.*
import core.{RateLimitDecision, RateLimitProfile, RateLimitStore, TokenBucketState}

import java.time.Instant

/**
 * In-memory rate limit store for testing purposes.
 *
 * Uses Ref for thread-safe state management, ensuring atomic operations
 * without external dependencies.
 */
class InMemoryRateLimitStore[F[_]: Sync](
    stateRef: Ref[F, Map[String, TokenBucketState]]
) extends RateLimitStore[F]:

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile
  ): F[RateLimitDecision] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)

      decision <- stateRef.modify { stateMap =>
        val currentState = stateMap.getOrElse(
          key,
          TokenBucketState(profile.capacity.toDouble, nowMs, 0L)
        )

        // Calculate refilled tokens
        val elapsedMs = nowMs - currentState.lastRefillEpochMs
        val tokensToAdd = (elapsedMs / 1000.0) * profile.refillRatePerSecond
        val refilledTokens = math.min(profile.capacity.toDouble, currentState.tokens + tokensToAdd)

        // Calculate reset time
        val tokensToFull = profile.capacity - refilledTokens
        val secondsToFull = (tokensToFull / profile.refillRatePerSecond).ceil.toLong
        val resetAt = Instant.ofEpochMilli(nowMs).plusSeconds(secondsToFull)

        if refilledTokens >= cost then
          val newTokens = refilledTokens - cost
          val newState = TokenBucketState(newTokens, nowMs, currentState.version + 1)
          val newMap = stateMap.updated(key, newState)
          (newMap, RateLimitDecision.Allowed(newTokens.toInt, resetAt))
        else
          val retryAfter = ((cost - refilledTokens) / profile.refillRatePerSecond).ceil.toInt
          (stateMap, RateLimitDecision.Rejected(math.max(1, retryAfter), resetAt))
      }
    yield decision

  override def getStatus(key: String, profile: RateLimitProfile): F[Option[RateLimitDecision.Allowed]] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      stateMap <- stateRef.get
    yield stateMap.get(key).map { state =>
      val elapsedMs = nowMs - state.lastRefillEpochMs
      val tokensToAdd = (elapsedMs / 1000.0) * profile.refillRatePerSecond
      val refilledTokens = math.min(profile.capacity.toDouble, state.tokens + tokensToAdd)
      
      // Calculate reset time
      val tokensToFull = profile.capacity - refilledTokens
      val secondsToFull = (tokensToFull / profile.refillRatePerSecond).ceil.toLong
      val resetAt = Instant.ofEpochMilli(nowMs).plusSeconds(secondsToFull)
      
      RateLimitDecision.Allowed(refilledTokens.toInt, resetAt)
    }

  override def healthCheck: F[Boolean] = Sync[F].pure(true)

object InMemoryRateLimitStore:
  def create[F[_]: Sync]: F[InMemoryRateLimitStore[F]] =
    Ref.of[F, Map[String, TokenBucketState]](Map.empty).map(new InMemoryRateLimitStore[F](_))