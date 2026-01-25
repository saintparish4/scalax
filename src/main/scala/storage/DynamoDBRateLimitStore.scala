package storage

import java.time.Instant
import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}

import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import config.DynamoDBConfig
import core.{
  RateLimitDecision, RateLimitProfile, RateLimitStore, TokenBucketState,
}
import cats.effect.{Async, Clock}
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*

/** DynamoDB-backed rate limit store with atomic operations.
  *
  * Uses Optimistic Concurrency Control (OCC) with a version field to ensure
  * atomic check-and-decrement operations. Under contention, operations are
  * retried with exponential backoff.
  *
  * Schema: pk (String, Hash Key): "ratelimit#{key}" tokens (Number): current
  * token count lastRefillEpochMs (Number): timestamp of last refill calculation
  * version (Number): monotonically increasing version for OCC ttl (Number):
  * epoch seconds for DynamoDB TTL cleanup
  */
class DynamoDBRateLimitStore[F[_]: Async: Logger](
    client: DynamoDbAsyncClient,
    config: DynamoDBConfig,
    maxRetries: Int = 5,
) extends RateLimitStore[F]:

  private val logger = Logger[F]

  private def completionStageToCompletableFuture[T](
      cs: CompletionStage[T],
  ): CompletableFuture[T] =
    val cf = new CompletableFuture[T]()
    cs.whenComplete((result, throwable) =>
      if throwable != null then cf.completeExceptionally(throwable)
      else cf.complete(result),
    )
    cf

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
  ): F[RateLimitDecision] =
    checkAndConsumeWithRetry(key, cost, profile, attempt = 0)

  /** Core atomic check-and-consume with retry logic.
    *
    * Pattern:
    *   1. Read current state (or initialize if new)
    *   2. Calculate refilled tokens based on elapsed time
    *   3. Decide allow/reject based on available tokens
    *   4. If allow: write new state with version condition
    *   5. If condition fails (concurrent modification): retry
    */
  private def checkAndConsumeWithRetry(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
      attempt: Int,
  ): F[RateLimitDecision] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      pk = s"ratelimit#$key"

      // Step 1: Read current state
      currentState <- getState(pk)

      // Step 2: Calculate refilled tokens
      state = currentState.getOrElse(initialState(profile, nowMs))
      elapsedMs = nowMs - state.lastRefillEpochMs
      tokensToAdd = elapsedMs / 1000.0 * profile.refillRatePerSecond
      refilledTokens = math.min(profile.capacity.toDouble, state.tokens + tokensToAdd)
      
      // Ensure refilledTokens doesn't exceed capacity due to floating point precision
      // Also ensure it's not negative and clamp to reasonable precision
      clampedRefilledTokens = math.min(profile.capacity.toDouble, math.max(0.0, refilledTokens))
      // Round to avoid floating point precision issues that could allow requests when tokens are effectively 0
      roundedTokens = math.round(clampedRefilledTokens * 1000.0) / 1000.0

      // Calculate reset time (when bucket will be full)
      tokensToFull = profile.capacity.toDouble - roundedTokens
      secondsToFull = (tokensToFull / profile.refillRatePerSecond).ceil.toLong
      resetAt = Instant.ofEpochMilli(nowMs).plusSeconds(secondsToFull)

      // Step 3: Decide
      decision <-
        if roundedTokens >= cost then
          // Allow: attempt atomic write
          val newTokens = math.max(0.0, roundedTokens - cost)
          val newState = TokenBucketState(newTokens, nowMs, state.version + 1)

          conditionalPutState(pk, newState, state.version, profile.ttlSeconds)
            .flatMap {
              case true =>
                // Success
                Async[F].pure(RateLimitDecision.Allowed(newTokens.toInt, resetAt))

              case false =>
                // Concurrent modification - retry
                if attempt < maxRetries then
                  logger.debug(s"OCC conflict for key=$key, attempt=${attempt +
                      1}") *>
                    // Exponential backoff: 10ms, 20ms, 40ms, 80ms, 160ms
                    Async[F].sleep(
                      scala.concurrent.duration
                        .Duration(10L * (1L << attempt), "ms"),
                    ) *>
                    checkAndConsumeWithRetry(key, cost, profile, attempt + 1)
                else
                  // Max retries exceeded - reject to be safe
                  logger.warn(
                    s"Max retries exceeded for key=$key, rejecting request",
                  ) *> Async[F].pure(RateLimitDecision.Rejected(1, resetAt))
            }
        else
          // Reject: not enough tokens
          val secondsUntilAllowed =
            ((cost - roundedTokens) / profile.refillRatePerSecond).ceil.toInt
          Async[F].pure(
            RateLimitDecision.Rejected(math.max(1, secondsUntilAllowed), resetAt),
          )
    yield decision

  override def getStatus(
      key: String,
      profile: RateLimitProfile,
  ): F[Option[TokenBucketState]] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      pk = s"ratelimit#$key"
      state <- getState(pk)
    yield state.map { s =>
      // Return with refilled tokens for accurate status
      val elapsedMs = nowMs - s.lastRefillEpochMs
      val tokensToAdd = elapsedMs / 1000.0 * profile.refillRatePerSecond
      val refilledTokens = math
        .min(profile.capacity.toDouble, s.tokens + tokensToAdd)
      s.copy(tokens = refilledTokens, lastRefillEpochMs = nowMs)
    }

  override def healthCheck: F[Boolean] =
    val request = DescribeTableRequest.builder().tableName(config.rateLimitTable)
      .build()

    Async[F].fromCompletableFuture(
      Async[F].delay(completionStageToCompletableFuture(client.describeTable(request))),
    ).map(_ => true).handleError(_ => false)

  // --- Private helpers ---

  private def initialState(
      profile: RateLimitProfile,
      nowMs: Long,
  ): TokenBucketState = TokenBucketState(
    tokens = profile.capacity.toDouble,
    lastRefillEpochMs = nowMs,
    version = 0L,
  )

  private def getState(pk: String): F[Option[TokenBucketState]] =
    val request = GetItemRequest.builder().tableName(config.rateLimitTable)
      .key(Map("pk" -> AttributeValue.builder().s(pk).build()).asJava)
      .consistentRead(true) // Strong consistency for accurate token count
      .build()

    Async[F].fromCompletableFuture(
      Async[F].delay(completionStageToCompletableFuture(client.getItem(request))),
    ).map { response =>
      val item = response.item()
      if item == null || item.isEmpty then None
      else
        Some(TokenBucketState(
          tokens = item.get("tokens").n().toDouble,
          lastRefillEpochMs = item.get("lastRefillEpochMs").n().toLong,
          version = item.get("version").n().toLong,
        ))
    }

  /** Conditionally put state, succeeding only if version matches. Returns true
    * if write succeeded, false if version mismatch.
    */
  private def conditionalPutState(
      pk: String,
      state: TokenBucketState,
      expectedVersion: Long,
      ttlSeconds: Long,
  ): F[Boolean] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      ttlEpoch = nowMs / 1000 + ttlSeconds

      item = Map(
        "pk" -> AttributeValue.builder().s(pk).build(),
        "tokens" -> AttributeValue.builder().n(state.tokens.toString).build(),
        "lastRefillEpochMs" -> AttributeValue.builder()
          .n(state.lastRefillEpochMs.toString).build(),
        "version" -> AttributeValue.builder().n(state.version.toString).build(),
        "ttl" -> AttributeValue.builder().n(ttlEpoch.toString).build(),
      )

      // Build request with condition based on whether this is new or existing
      request =
        if expectedVersion == 0L then
          // New item: condition that pk doesn't exist
          PutItemRequest.builder().tableName(config.rateLimitTable)
            .item(item.asJava).conditionExpression("attribute_not_exists(pk)")
            .build()
        else
          // Existing item: condition that version matches
          PutItemRequest.builder().tableName(config.rateLimitTable)
            .item(item.asJava).conditionExpression("version = :expectedVersion")
            .expressionAttributeValues(
              Map(
                ":expectedVersion" -> AttributeValue.builder()
                  .n(expectedVersion.toString).build(),
              ).asJava,
            ).build()

      result <- Async[F].fromCompletableFuture(
        Async[F].delay(completionStageToCompletableFuture(client.putItem(request))),
      ).map(_ => true).recover {
        case e: CompletionException if isConditionalCheckFailed(e) => false
        case e: ConditionalCheckFailedException => false
      }
    yield result

  private def isConditionalCheckFailed(e: CompletionException): Boolean =
    e.getCause match
      case _: ConditionalCheckFailedException => true
      case _ => false
