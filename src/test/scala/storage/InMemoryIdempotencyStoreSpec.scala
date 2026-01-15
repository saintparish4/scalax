package storage

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import core.{CachedResponse, IdempotencyResult}

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

class InMemoryIdempotencyStoreSpec
    extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  "InMemoryIdempotencyStore" - {

    "should return New for first request" in {
      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        result <- store.checkOrCreate("new-key", ttlSeconds = 3600)
      } yield result

      test.asserting { result =>
        result shouldBe a[IdempotencyResult.New]
        result.asInstanceOf[IdempotencyResult.New].idempotencyKey shouldBe
          "new-key"
      }
    }

    "should return Duplicate for second request" in {
      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        _ <- store.checkOrCreate("dup-key", ttlSeconds = 3600)
        result <- store.checkOrCreate("dup-key", ttlSeconds = 3600)
      } yield result

      test.asserting(result => result shouldBe a[IdempotencyResult.Duplicate])
    }

    "should return None cachedResponse when pending" in {
      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        _ <- store.checkOrCreate("pending-key", ttlSeconds = 3600)
        result <- store.checkOrCreate("pending-key", ttlSeconds = 3600)
      } yield result

      test.asserting { result =>
        val dup = result.asInstanceOf[IdempotencyResult.Duplicate]
        dup.cachedResponse shouldBe None
      }
    }

    "should store and return cached response" in {
      val response = CachedResponse(201, """{"id": 1}""", Map("X-Id" -> "abc"))

      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        _ <- store.checkOrCreate("response-key", ttlSeconds = 3600)
        _ <- store.storeResponse("response-key", response)
        result <- store.checkOrCreate("response-key", ttlSeconds = 3600)
      } yield result

      test.asserting { result =>
        val dup = result.asInstanceOf[IdempotencyResult.Duplicate]
        dup.cachedResponse shouldBe defined
        dup.cachedResponse.get.statusCode shouldBe 201
      }
    }

    "should isolate different keys" in {
      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        r1 <- store.checkOrCreate("key-1", ttlSeconds = 3600)
        r2 <- store.checkOrCreate("key-2", ttlSeconds = 3600)
        r3 <- store.checkOrCreate("key-1", ttlSeconds = 3600)
      } yield (r1, r2, r3)

      test.asserting { case (r1, r2, r3) =>
        r1 shouldBe a[IdempotencyResult.New]
        r2 shouldBe a[IdempotencyResult.New]
        r3 shouldBe a[IdempotencyResult.Duplicate]
      }
    }

    "should handle concurrent first-writer-wins" in {
      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        results <- (1 to 10).toList.parTraverse(_ =>
          store.checkOrCreate("concurrent-key", ttlSeconds = 3600),
        )
        newCount = results.count(_.isInstanceOf[IdempotencyResult.New])
      } yield newCount

      test.asserting(newCount =>
        // Exactly one should win
        newCount shouldBe 1,
      )
    }

    "health check should return true" in {
      val test = for {
        store <- InMemoryIdempotencyStore.create[IO]
        healthy <- store.healthCheck
      } yield healthy

      test.asserting(healthy => healthy shouldBe true)
    }
  }
