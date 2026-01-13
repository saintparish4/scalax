package storage

import java.net.URI

import cats.effect.{Async, Resource}
import config.AwsConfig
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider,
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

/** Factory for AWS SDK async clients with proper resource management
  *
  * All clients are wrapped in Resource to ensure proper cleanup on shutdown
  * Supports both real AWS and Localstack for development
  */
object AwsClients:

  /** Create a DynamoDB async client as a managed Resource
    *
    * The client will be properly closed when the Resource is released, ensuring
    * no connection leaks
    */
  def dynamoDbClient[F[_]: Async](
      config: AwsConfig,
  ): Resource[F, DynamoDbAsyncClient] = Resource.make {
    Async[F].delay {
      val builder = DynamoDbAsyncClient.builder().region(Region.of(config.region))

      // Configure credentials based on environment
      val withCredentials =
        if config.localstack then
          builder.credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test"),
          ))
        else builder.credentialsProvider(DefaultCredentialsProvider.create())

      // Override endpoint for Localstack or custom endpoint
      val withEndpoint = config.dynamodbEndpoint match
        case Some(endpoint) => withCredentials
            .endpointOverride(URI.create(endpoint))
        case None if config.localstack =>
          withCredentials.endpointOverride(URI.create("http://localhost:4566"))
        case None => withCredentials

      withEndpoint.build()
    }
  }(client => Async[F].delay(client.close()))

  /** Create a Kinesis async client as a managed Resource
    */
  def kinesisClient[F[_]: Async](
      config: AwsConfig,
  ): Resource[F, KinesisAsyncClient] = Resource.make {
    Async[F].delay {
      val builder = KinesisAsyncClient.builder().region(Region.of(config.region))

      val withCredentials =
        if config.localstack then
          builder.credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test"),
          ))
        else builder.credentialsProvider(DefaultCredentialsProvider.create())

      val withEndpoint = config.kinesisEndpoint match
        case Some(endpoint) => withCredentials
            .endpointOverride(URI.create(endpoint))
        case None if config.localstack =>
          withCredentials.endpointOverride(URI.create("http://localhost:4566"))
        case None => withCredentials

      withEndpoint.build()
    }
  }(client => Async[F].delay(client.close()))
