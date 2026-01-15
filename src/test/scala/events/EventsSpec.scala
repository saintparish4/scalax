package events

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import io.circe.parser.*
import io.circe.syntax.*

class EventsSpec extends AnyFreeSpec with Matchers:

  "RateLimitEvent" - {

    "Allowed event" - {
      val event = RateLimitEvent.Allowed(
        timestamp = 1705312200000L,
        apiKey = "api-key-123",
        requestKey = "user:456",
        endpoint = Some("/api/v1/resource"),
        tokensRemaining = 95,
        cost = 5,
      )

      "should have correct event type" in {
        event.eventType shouldBe "rate_limit_allowed"
      }

      "should use apiKey as partition key" in {
        event.partitionKey shouldBe "api-key-123"
      }

      "should serialize to JSON" in {
        val json = event.asJson
        json.hcursor.get[Long]("timestamp").toOption shouldBe
          Some(1705312200000L)
        json.hcursor.get[String]("apiKey").toOption shouldBe Some("api-key-123")
        json.hcursor.get[Int]("tokensRemaining").toOption shouldBe Some(95)
        json.hcursor.get[Int]("cost").toOption shouldBe Some(5)
      }
    }

    "Rejected event" - {
      val event = RateLimitEvent.Rejected(
        timestamp = 1705312200000L,
        apiKey = "api-key-789",
        requestKey = "user:999",
        endpoint = None,
        retryAfterSeconds = 10,
        reason = "rate_limit_exceeded",
      )

      "should have correct event type" in {
        event.eventType shouldBe "rate_limit_rejected"
      }

      "should use apiKey as partition key" in {
        event.partitionKey shouldBe "api-key-789"
      }

      "should serialize to JSON" in {
        val json = event.asJson
        json.hcursor.get[Int]("retryAfterSeconds").toOption shouldBe Some(10)
        json.hcursor.get[String]("reason").toOption shouldBe
          Some("rate_limit_exceeded")
      }
    }

    "IdempotencyHit event" - {
      val event = RateLimitEvent.IdempotencyHit(
        timestamp = 1705312200000L,
        apiKey = "api-key-abc",
        idempotencyKey = "payment:order-123",
      )

      "should have correct event type" in {
        event.eventType shouldBe "idempotency_hit"
      }

      "should use apiKey as partition key" in {
        event.partitionKey shouldBe "api-key-abc"
      }

      "should serialize to JSON" in {
        val json = event.asJson
        json.hcursor.get[String]("idempotencyKey").toOption shouldBe
          Some("payment:order-123")
      }
    }

    "Base RateLimitEvent encoder" - {
      "should encode Allowed events" in {
        val event: RateLimitEvent = RateLimitEvent.Allowed(
          timestamp = 1L,
          apiKey = "key",
          requestKey = "req",
          endpoint = None,
          tokensRemaining = 10,
          cost = 1,
        )
        val json = event.asJson.noSpaces
        json should include("tokensRemaining")
      }

      "should encode Rejected events" in {
        val event: RateLimitEvent = RateLimitEvent.Rejected(
          timestamp = 1L,
          apiKey = "key",
          requestKey = "req",
          endpoint = None,
          retryAfterSeconds = 5,
          reason = "test",
        )
        val json = event.asJson.noSpaces
        json should include("retryAfterSeconds")
      }

      "should encode IdempotencyHit events" in {
        val event: RateLimitEvent = RateLimitEvent.IdempotencyHit(
          timestamp = 1L,
          apiKey = "key",
          idempotencyKey = "idem-key",
        )
        val json = event.asJson.noSpaces
        json should include("idempotencyKey")
      }
    }
  }
