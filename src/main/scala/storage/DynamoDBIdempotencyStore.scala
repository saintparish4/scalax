package storage

import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}

import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import config.DynamoDBConfig
import core.{CachedResponse, IdempotencyResult, IdempotencyStore}
import cats.effect.{Async, Clock}
import cats.syntax.all.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*

/** DynamoDB-backed idempotency store with first-writer-wins semantics.
  *
  * Uses conditional writes to ensure only the first request with a given
  * idempotency key is processed. Subsequent requests receive the cached
  * response.
  *
  * Schema: pk (String, Hash Key): "idempotency#{key}" status (String):
  * "pending" | "completed" response (String): JSON-encoded cached response
  * (when completed) createdAt (Number): epoch ms when key was created ttl
  * (Number): epoch seconds for DynamoDB TTL cleanup
  */
class DynamoDBIdempotencyStore[F[_]: Async: Logger](
    client: DynamoDbAsyncClient,
    config: DynamoDBConfig,
) extends IdempotencyStore[F]:

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

  override def checkOrCreate(
      key: String,
      ttlSeconds: Long,
  ): F[IdempotencyResult] =
    for
      nowMs <- Clock[F].realTime.map(_.toMillis)
      pk = s"idempotency#$key"
      ttlEpoch = nowMs / 1000 + ttlSeconds

      // Try to create new item with condition that it doesn't exist
      item = Map(
        "pk" -> AttributeValue.builder().s(pk).build(),
        "status" -> AttributeValue.builder().s("pending").build(),
        "createdAt" -> AttributeValue.builder().n(nowMs.toString).build(),
        "ttl" -> AttributeValue.builder().n(ttlEpoch.toString).build(),
      )

      request = PutItemRequest.builder().tableName(config.idempotencyTable)
        .item(item.asJava).conditionExpression("attribute_not_exists(pk)").build()

      result <- Async[F].fromCompletableFuture(
        Async[F].delay(completionStageToCompletableFuture(client.putItem(request))),
      ).map(_ => IdempotencyResult.New(key)).recoverWith {
        case e: CompletionException if isConditionalCheckFailed(e) =>
          // Key exists - fetch the existing record
          getExisting(pk, key)
        case e: ConditionalCheckFailedException => getExisting(pk, key)
      }
    yield result

  override def storeResponse(key: String, response: CachedResponse): F[Unit] =
    for
      pk <- Async[F].pure(s"idempotency#$key")
      responseJson = response.asJson.noSpaces

      request = UpdateItemRequest.builder().tableName(config.idempotencyTable)
        .key(Map("pk" -> AttributeValue.builder().s(pk).build()).asJava)
        .updateExpression("SET #status = :completed, #response = :response")
        .expressionAttributeNames(
          Map("#status" -> "status", "#response" -> "response").asJava,
        ).expressionAttributeValues(
          Map(
            ":completed" -> AttributeValue.builder().s("completed").build(),
            ":response" -> AttributeValue.builder().s(responseJson).build(),
          ).asJava,
        ).build()

      _ <- Async[F].fromCompletableFuture(
        Async[F].delay(completionStageToCompletableFuture(client.updateItem(request))),
      ).void.handleErrorWith(e =>
        logger.error(e)(s"Failed to store response for idempotency key=$key"),
      )
    yield ()

  override def healthCheck: F[Boolean] =
    val request = DescribeTableRequest.builder()
      .tableName(config.idempotencyTable).build()

    Async[F].fromCompletableFuture(
      Async[F].delay(completionStageToCompletableFuture(client.describeTable(request))),
    ).map(_ => true).handleError(_ => false)

  // --- Private helpers ---

  private def getExisting(pk: String, key: String): F[IdempotencyResult] =
    val request = GetItemRequest.builder().tableName(config.idempotencyTable)
      .key(Map("pk" -> AttributeValue.builder().s(pk).build()).asJava)
      .consistentRead(true).build()

    Async[F].fromCompletableFuture(
      Async[F].delay(completionStageToCompletableFuture(client.getItem(request))),
    ).flatMap { response =>
      val item = response.item()
      if item == null || item.isEmpty then
        // Race condition: item was deleted between our failed write and read
        // Treat as new
        logger.debug(s"Idempotency key=$key disappeared, treating as new") *>
          Async[F].pure(IdempotencyResult.New(key))
      else
        val status = item.get("status").s()
        val cachedResponse = Option(item.get("response")).map(_.s())
          .flatMap(json => decode[CachedResponse](json).toOption)

        Async[F].pure(
          IdempotencyResult
            .Duplicate(key, cachedResponse.filter(_ => status == "completed")),
        )
    }

  private def isConditionalCheckFailed(e: CompletionException): Boolean =
    e.getCause match
      case _: ConditionalCheckFailedException => true
      case _ => false
