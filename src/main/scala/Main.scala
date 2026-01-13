package com.ratelimiter

import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, LoggerFactory}

import com.comcast.ip4s.*

import com.ratelimiter.api.Routes
import config.AppConfig
import events.{EventPublisher, KinesisPublisher}
import storage.{AwsClients, DynamoDBIdempotencyStore, DynamoDBRateLimitStore}
import cats.effect.*
import cats.syntax.all.*

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] = application[IO]
    .use(_ => IO.never).as(ExitCode.Success)

  /** Build the application as a Resource that properly manages all lifecycles.
    *
    * Resource acquisition order:
    *   1. Logger
    *   2. Configuration
    *   3. AWS clients (DynamoDB, Kinesis)
    *   4. Storage implementations
    *   5. HTTP server
    *
    * On shutdown, resources are released in reverse order.
    */
  def application[F[_]: Async]: Resource[F, Unit] =
    for
      // 1. Create logger
      given Logger[F] <- Resource.eval(Slf4jLogger.create[F])
      logger = summon[Logger[F]]

      _ <- Resource.eval(logger.info("Starting Rate Limiter Platform..."))

      // 2. Load configuration
      config <- Resource.eval(AppConfig.load[F])
      _ <- Resource.eval(logger.info(s"Loaded configuration: server=${config
          .server.host}:${config.server.port}"))

      // 3. Create AWS clients with proper lifecycle management
      dynamoClient <- AwsClients.dynamoDbClient[F](config.aws)
      _ <- Resource.eval(logger.info("DynamoDB client initialized"))

      kinesisClient <- AwsClients.kinesisClient[F](config.aws)
      _ <- Resource.eval(logger.info("Kinesis client initialized"))

      // 4. Create storage implementations
      rateLimitStore = new DynamoDBRateLimitStore[F](
        dynamoClient,
        config.dynamodb,
        maxRetries = 5,
      )

      idempotencyStore =
        new DynamoDBIdempotencyStore[F](dynamoClient, config.dynamodb)

      // 5. Create event publisher
      eventPublisher: EventPublisher[F] =
        if config.kinesis.enabled then
          new KinesisPublisher[F](kinesisClient, config.kinesis)
        else EventPublisher.noop[F]

      _ <- Resource
        .eval(logger.info(s"Event publishing enabled: ${config.kinesis.enabled}"))

      // 6. Create routes
      routes =
        new Routes[F](rateLimitStore, idempotencyStore, eventPublisher, config)

      // 7. Parse host/port
      host <- Resource.eval(Async[F].fromOption(
        Host.fromString(config.server.host),
        new IllegalArgumentException(s"Invalid host: ${config.server.host}"),
      ))

      port <- Resource.eval(Async[F].fromOption(
        Port.fromInt(config.server.port),
        new IllegalArgumentException(s"Invalid port: ${config.server.port}"),
      ))

      // 8. Start HTTP server
      _ <- EmberServerBuilder.default[F].withHost(host).withPort(port)
        .withHttpApp(routes.httpApp)
        .withShutdownTimeout(scala.concurrent.duration.Duration(30, "s")).build
        .evalTap(_ =>
          logger.info(s"Server started on http://${config.server.host}:${config
              .server.port}"),
        )
    yield ()
