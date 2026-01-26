package storage

import cats.effect.*
import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import java.time.Instant 

import core.{RateLimitDecision, RateLimitProfile, RateLimitStore} 

/**
 * DynamoDB implementation of RateLimitStore using token bucket algorithm.
 *
 * Uses optimistic concurrency control (OCC) via version field to prevent
 * race conditions when multiple instances try to update the same key.
 *
 * Table Schema:
 * - pk (S): Partition key - "ratelimit#<key>"
 * - tokens (N): Current token count
 * - lastRefillMs (N): Timestamp of last refill calculation
 * - version (N): Version for OCC
 * - ttl (N): TTL for automatic cleanup
 */
class DynamoDBRateLimitStore[F[_]: Async](
    client: DynamoDbAsyncClient,
    tableName: String
) extends RateLimitStore[F]:

  private val MaxRetries = 10

  override def checkAndConsume(
      key: String,
      cost: Int,
      profile: RateLimitProfile
  ): F[RateLimitDecision] =
    checkAndConsumeWithRetry(key, cost, profile, MaxRetries)

  private def checkAndConsumeWithRetry(
      key: String,
      cost: Int,
      profile: RateLimitProfile,
      retriesRemaining: Int
  ): F[RateLimitDecision] =
    for
      now <- Clock[F].realTime.map(_.toMillis)
      
      // Get current state
      currentState <- getOrInitState(key, profile, now)
      
      // Calculate refilled tokens
      elapsed = (now - currentState.lastRefillMs) / 1000.0
      tokensToAdd = elapsed * profile.refillRatePerSecond
      refilledTokens = math.min(profile.capacity.toDouble, currentState.tokens + tokensToAdd)
      
      // Decide and attempt update
      decision <- if refilledTokens >= cost then
        val newTokens = refilledTokens - cost
        val newState = TokenBucketState(newTokens, now, currentState.version + 1)
        
        attemptUpdate(key, currentState.version, newState, profile.ttlSeconds).flatMap {
          case true =>
            val resetAt = calculateResetAt(now, newTokens, profile)
            Async[F].pure(RateLimitDecision.Allowed(newTokens.toInt, resetAt))
          case false =>
            // OCC conflict - retry with small delay to reduce contention
            if retriesRemaining > 0 then
              Async[F].sleep(1.millis) *>
                checkAndConsumeWithRetry(key, cost, profile, retriesRemaining - 1)
            else
              // Give up, reject to be safe
              val resetAt = calculateResetAt(now, refilledTokens, profile)
              Async[F].pure(RateLimitDecision.Rejected(1, resetAt))
        }
      else
        val retryAfter = math.ceil((cost - refilledTokens) / profile.refillRatePerSecond).toInt.max(1)
        val resetAt = calculateResetAt(now, refilledTokens, profile)
        Async[F].pure(RateLimitDecision.Rejected(retryAfter, resetAt))
    yield decision

  override def getStatus(
      key: String,
      profile: RateLimitProfile
  ): F[Option[RateLimitDecision.Allowed]] =
    for
      now <- Clock[F].realTime.map(_.toMillis)
      maybeState <- getState(key)
      result = maybeState.map { state =>
        val elapsed = (now - state.lastRefillMs) / 1000.0
        val tokensToAdd = elapsed * profile.refillRatePerSecond
        val refilledTokens = math.min(profile.capacity.toDouble, state.tokens + tokensToAdd)
        val resetAt = calculateResetAt(now, refilledTokens, profile)
        RateLimitDecision.Allowed(refilledTokens.toInt, resetAt)
      }
    yield result

  override def healthCheck: F[Boolean] =
    Async[F].fromCompletableFuture(
      Async[F].delay(
        client.describeTable(
          DescribeTableRequest.builder().tableName(tableName).build()
        ).toCompletableFuture
      )
    ).map(_ => true).handleError(_ => false)

  private def getOrInitState(
      key: String,
      profile: RateLimitProfile,
      now: Long
  ): F[TokenBucketState] =
    getState(key).map(_.getOrElse(
      TokenBucketState(profile.capacity.toDouble, now, 0L)
    ))

  private def getState(key: String): F[Option[TokenBucketState]] =
    val request = GetItemRequest.builder()
      .tableName(tableName)
      .key(Map("pk" -> AttributeValue.builder().s(s"ratelimit#$key").build()).asJava)
      .consistentRead(true)
      .build()

    Async[F].fromCompletableFuture(
      Async[F].delay(client.getItem(request).toCompletableFuture)
    ).map { response =>
      if response.hasItem && !response.item().isEmpty then
        Some(parseState(response.item().asScala.toMap))
      else
        None
    }

  private def parseState(item: Map[String, AttributeValue]): TokenBucketState =
    TokenBucketState(
      tokens = item.get("tokens").map(_.n().toDouble).getOrElse(0.0),
      lastRefillMs = item.get("lastRefillMs").map(_.n().toLong).getOrElse(0L),
      version = item.get("version").map(_.n().toLong).getOrElse(0L)
    )

  private def attemptUpdate(
      key: String,
      expectedVersion: Long,
      newState: TokenBucketState,
      ttlSeconds: Long
  ): F[Boolean] =
    val ttl = (System.currentTimeMillis() / 1000) + ttlSeconds
    
    val item = Map(
      "pk" -> AttributeValue.builder().s(s"ratelimit#$key").build(),
      "tokens" -> AttributeValue.builder().n(newState.tokens.toString).build(),
      "lastRefillMs" -> AttributeValue.builder().n(newState.lastRefillMs.toString).build(),
      "version" -> AttributeValue.builder().n(newState.version.toString).build(),
      "ttl" -> AttributeValue.builder().n(ttl.toString).build()
    )

    val requestBuilder = PutItemRequest.builder()
      .tableName(tableName)
      .item(item.asJava)

    // Add conditional expression for OCC
    val request = if expectedVersion == 0L then
      // First write - item should not exist
      requestBuilder
        .conditionExpression("attribute_not_exists(pk)")
        .build()
    else
      // Update - version must match
      requestBuilder
        .conditionExpression("version = :expectedVersion")
        .expressionAttributeValues(Map(
          ":expectedVersion" -> AttributeValue.builder().n(expectedVersion.toString).build()
        ).asJava)
        .build()

    Async[F].fromCompletableFuture(
      Async[F].delay(client.putItem(request).toCompletableFuture)
    ).map(_ => true)
      .recover {
        case _: ConditionalCheckFailedException => false
      }

  private def calculateResetAt(
      now: Long,
      currentTokens: Double,
      profile: RateLimitProfile
  ): Instant =
    val tokensToFull = profile.capacity - currentTokens
    val secondsToFull = (tokensToFull / profile.refillRatePerSecond).ceil.toLong
    Instant.ofEpochMilli(now + (secondsToFull * 1000))

private case class TokenBucketState(
    tokens: Double,
    lastRefillMs: Long,
    version: Long
)

object DynamoDBRateLimitStore:
  def apply[F[_]: Async](
      client: DynamoDbAsyncClient,
      tableName: String
  ): DynamoDBRateLimitStore[F] =
    new DynamoDBRateLimitStore[F](client, tableName)
