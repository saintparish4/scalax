package events

import java.util.concurrent.{CompletableFuture, CompletionStage}

import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

import org.typelevel.log4cats.Logger

import config.KinesisConfig
import cats.effect.Async
import cats.syntax.all.*
import io.circe.syntax.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.*

/** Kinesis-backed event publisher for rate limit events.
  *
  * Events are published asynchronously. Failures are logged but do not affect
  * the rate limit decision returned to clients.
  */
class KinesisPublisher[F[_]: Async: Logger](
    client: KinesisAsyncClient,
    config: KinesisConfig,
) extends EventPublisher[F]:

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

  override def publish(event: RateLimitEvent): F[Unit] =
    if !config.enabled then Async[F].unit
    else
      val json = event.asJson.noSpaces
      val bytes = SdkBytes.fromUtf8String(json)

      val request = PutRecordRequest.builder().streamName(config.streamName)
        .partitionKey(event.partitionKey).data(bytes).build()

      Async[F].fromCompletableFuture(
        Async[F].delay(completionStageToCompletableFuture(client.putRecord(request))),
      ).void.handleErrorWith(e =>
        logger.warn(e)(s"Failed to publish event: ${event.eventType}"),
      )

  override def publishBatch(events: List[RateLimitEvent]): F[Unit] =
    if !config.enabled || events.isEmpty then Async[F].unit
    else
      // Kinesis PutRecords supports up to 500 records per call
      val batches = events.grouped(500).toList

      batches.traverse_ { batch =>
        val records = batch.map(event =>
          PutRecordsRequestEntry.builder().partitionKey(event.partitionKey)
            .data(SdkBytes.fromUtf8String(event.asJson.noSpaces)).build(),
        )

        val request = PutRecordsRequest.builder().streamName(config.streamName)
          .records(records.asJava).build()

        Async[F].fromCompletableFuture(
          Async[F].delay(completionStageToCompletableFuture(client.putRecords(request))),
        ).flatMap(response =>
          // Log if any records failed
          if response.failedRecordCount() > 0 then
            logger.warn(s"Failed to publish ${response
                .failedRecordCount()} of ${batch.size} events")
          else Async[F].unit,
        ).handleErrorWith(e =>
          logger.warn(e)(s"Failed to publish batch of ${batch.size} events"),
        )
      }

  override def healthCheck: F[Boolean] =
    val request = DescribeStreamSummaryRequest.builder()
      .streamName(config.streamName).build()

    Async[F].fromCompletableFuture(
      Async[F].delay(completionStageToCompletableFuture(client.describeStreamSummary(request))),
    ).map(_ => true).handleError(_ => false)
