package config

import cats.effect.Sync
import pureconfig.*
import pureconfig.generic.derivation.default.*

// Server configuration
case class ServerConfig(host: String, port: Int) derives ConfigReader

// AWS configuration for SDK clients
case class AwsConfig(
    region: String,
    localstack: Boolean,
    dynamodbEndpoint: Option[String],
    kinesisEndpoint: Option[String],
) derives ConfigReader

// DynamoDB table configuration
case class DynamoDBConfig(rateLimitTable: String, idempotencyTable: String)
    derives ConfigReader

// Kinesis stream configuration
case class KinesisConfig(streamName: String, enabled: Boolean)
    derives ConfigReader

// Rate limiting defaults
case class RateLimitConfig(
    defaultCapacity: Int,
    defaultRefillRatePerSecond: Double,
    defaultTtlSeconds: Long,
) derives ConfigReader

// Root application configuration
case class AppConfig(
    server: ServerConfig,
    aws: AwsConfig,
    dynamodb: DynamoDBConfig,
    kinesis: KinesisConfig,
    rateLimit: RateLimitConfig,
) derives ConfigReader

object AppConfig:
  def load[F[_]: Sync]: F[AppConfig] = Sync[F]
    .delay(ConfigSource.default.loadOrThrow[AppConfig])
