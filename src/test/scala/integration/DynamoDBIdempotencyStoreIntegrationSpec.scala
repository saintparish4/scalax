package integration

import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import core.{CachedResponse, IdempotencyResult}
import storage.DynamoDBIdempotencyStore

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

/** Integration tests for DynamoDBIdempotencyStore.
  *
  * Tests first-writer-wins semantics and response caching.
  */
class DynamoDBIdempotencyStoreIntegrationSpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers
    with LocalStackIntegrationSpec
    with BeforeAndAfterEach {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  lazy val store: DynamoDBIdempotencyStore[IO] =
    new DynamoDBIdempotencyStore[IO](dynamoDbClient, testDynamoDBConfig)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    clearTable(testDynamoDBConfig.idempotencyTable)
  }

  "DynamoDBIdempotencyStore" - {

    "should return New for first request" in
      store.checkOrCreate("new-key-1", ttlSeconds = 3600).asserting { result =>
        result shouldBe a[IdempotencyResult.New]
        result.asInstanceOf[IdempotencyResult.New].idempotencyKey shouldBe
          "new-key-1"
      }

    "should return Duplicate for second request with same key" in {
      val test = for {
        first <- store.checkOrCreate("dup-key", ttlSeconds = 3600)
        second <- store.checkOrCreate("dup-key", ttlSeconds = 3600)
      } yield (first, second)

      test.asserting { case (first, second) =>
        first shouldBe a[IdempotencyResult.New]
        second shouldBe a[IdempotencyResult.Duplicate]
        second.asInstanceOf[IdempotencyResult.Duplicate].idempotencyKey shouldBe
          "dup-key"
      }
    }

    "should return Duplicate without response when pending" in {
      val test = for {
        _ <- store.checkOrCreate("pending-key", ttlSeconds = 3600)
        result <- store.checkOrCreate("pending-key", ttlSeconds = 3600)
      } yield result

      test.asserting { result =>
        result shouldBe a[IdempotencyResult.Duplicate]
        val dup = result.asInstanceOf[IdempotencyResult.Duplicate]
        dup.cachedResponse shouldBe None // No response stored yet
      }
    }

    "should store and return cached response" in {
      val cachedResponse = CachedResponse(
        statusCode = 201,
        body = """{"id": "order-123", "status": "created"}""",
        headers = Map("X-Request-Id" -> "req-456"),
      )

      val test = for {
        // First request - mark as new
        _ <- store.checkOrCreate("response-key", ttlSeconds = 3600)

        // Store the response
        _ <- store.storeResponse("response-key", cachedResponse)

        // Second request - should get cached response
        result <- store.checkOrCreate("response-key", ttlSeconds = 3600)
      } yield result

      test.asserting { result =>
        result shouldBe a[IdempotencyResult.Duplicate]
        val dup = result.asInstanceOf[IdempotencyResult.Duplicate]
        dup.cachedResponse shouldBe defined

        val response = dup.cachedResponse.get
        response.statusCode shouldBe 201
        response.body should include("order-123")
        response.headers should contain("X-Request-Id" -> "req-456")
      }
    }

    "should handle concurrent first-writer-wins" in {
      val concurrentRequests = 10

      val test = for {
        results <- (1 to concurrentRequests).toList.parTraverse(_ =>
          store.checkOrCreate("concurrent-idem-key", ttlSeconds = 3600),
        )

        newCount = results.count(_.isInstanceOf[IdempotencyResult.New])
        dupCount = results.count(_.isInstanceOf[IdempotencyResult.Duplicate])
      } yield (newCount, dupCount)

      test.asserting { case (newCount, dupCount) =>
        // Exactly ONE should win (first-writer-wins)
        newCount shouldBe 1
        dupCount shouldBe 9
      }
    }

    "should isolate different keys" in {
      val test = for {
        r1 <- store.checkOrCreate("key-a", ttlSeconds = 3600)
        r2 <- store.checkOrCreate("key-b", ttlSeconds = 3600)
        r3 <- store.checkOrCreate("key-c", ttlSeconds = 3600)
      } yield (r1, r2, r3)

      test.asserting { case (r1, r2, r3) =>
        r1 shouldBe a[IdempotencyResult.New]
        r2 shouldBe a[IdempotencyResult.New]
        r3 shouldBe a[IdempotencyResult.New]
      }
    }

    "should pass health check" in
      store.healthCheck.asserting(healthy => healthy shouldBe true)

    "should handle complex response bodies" in {
      val complexResponse = CachedResponse(
        statusCode = 200,
        body =
          """{
          "items": [
            {"id": 1, "name": "Item 1", "tags": ["a", "b"]},
            {"id": 2, "name": "Item 2", "tags": ["c", "d"]}
          ],
          "pagination": {
            "page": 1,
            "total": 100
          },
          "metadata": {
            "requestId": "abc-123",
            "timestamp": "2024-01-15T10:30:00Z"
          }
        }""",
        headers =
          Map("Content-Type" -> "application/json", "X-Trace-Id" -> "trace-789"),
      )

      val test = for {
        _ <- store.checkOrCreate("complex-key", ttlSeconds = 3600)
        _ <- store.storeResponse("complex-key", complexResponse)
        result <- store.checkOrCreate("complex-key", ttlSeconds = 3600)
      } yield result

      test.asserting { result =>
        val dup = result.asInstanceOf[IdempotencyResult.Duplicate]
        dup.cachedResponse shouldBe defined

        val response = dup.cachedResponse.get
        response.body should include("Item 1")
        response.body should include("pagination")
        response.headers should have size 2
      }
    }
  }
}
