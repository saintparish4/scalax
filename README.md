# Scala Distributed Rate Limiter Platform

A production-grade distributed rate limiting and idempotency platform built with Scala 3 and AWS services.

## Product Vision

This platform provides a scalable, high-performance rate limiting service that enables applications to:
- **Control API usage** with configurable rate limits using token bucket algorithm
- **Ensure idempotency** for critical operations with first-writer-wins semantics
- **Stream events** to AWS Kinesis for real-time analytics and monitoring
- **Scale horizontally** to handle high-throughput workloads with sub-10ms latency

Designed for production use with AWS-native services, the platform offers distributed rate limiting that works seamlessly across multiple service instances while maintaining consistency and performance.

## Prerequisites

- **Scala 3.7.4+**
- **SBT 1.9+**
- **Java 17+**
- **Docker** (for local development with Localstack)
- **AWS CLI** (for AWS deployment)

## Tech Stack

### Core Application
- **Scala 3.7.4** - Modern functional programming language
- **HTTP4s 0.23.32** - Type-safe HTTP server and client
- **Cats Effect 3.6.3** - Functional effects and concurrency
- **Circe 0.14.15** - JSON serialization
- **PureConfig 0.17.9** - Type-safe configuration management
- **Log4Cats 2.7.1** - Structured logging

### AWS Services
- **DynamoDB** - NoSQL database for rate limit and idempotency state
- **Kinesis** - Data streaming for event analytics
- **CloudWatch** - Logging and metrics

### Development Tools
- **SBT** - Build tool
- **ScalaTest** - Testing framework
- **Testcontainers** - Integration testing with Localstack

## Quick Start

1. **Start Localstack** (for local development)
   ```bash
   docker run -d -p 4566:4566 localstack/localstack
   ```

2. **Create required AWS resources**
   ```bash
   # DynamoDB tables
   aws dynamodb create-table --endpoint-url http://localhost:4566 \
     --table-name rate-limits \
     --attribute-definitions AttributeName=pk,AttributeType=S \
     --key-schema AttributeName=pk,KeyType=HASH \
     --billing-mode PAY_PER_REQUEST
   
   aws dynamodb create-table --endpoint-url http://localhost:4566 \
     --table-name idempotency \
     --attribute-definitions AttributeName=pk,AttributeType=S \
     --key-schema AttributeName=pk,KeyType=HASH \
     --billing-mode PAY_PER_REQUEST
   
   # Kinesis stream
   aws kinesis create-stream --endpoint-url http://localhost:4566 \
     --stream-name rate-limit-events --shard-count 1
   ```

3. **Configure and run**
   ```bash
   # Set localstack mode
   export USE_LOCALSTACK=true
   export DYNAMODB_ENDPOINT=http://localhost:4566
   export KINESIS_ENDPOINT=http://localhost:4566
   
   # Run the application
   sbt run
   ```

   Server starts on `http://localhost:8080`

## API Examples

**Check Rate Limit:**
```bash
curl -X POST http://localhost:8080/v1/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key": "user:12345", "cost": 1}'
```

**Check Idempotency:**
```bash
curl -X POST http://localhost:8080/v1/idempotency/check \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey": "payment:abc-123", "ttl": 86400}'
```

**Health Check:**
```bash
curl http://localhost:8080/health
```

## Project Structure

```
src/main/scala/
├── api/          # HTTP API endpoints
├── core/         # Core business logic and interfaces
├── storage/      # DynamoDB storage implementations
├── events/       # Kinesis event publishing
└── config/       # Configuration management
```

## Development

```bash
# Compile
sbt compile

# Run tests
sbt test

# Format code
sbt scalafmtAll

# Build Docker image
sbt docker:publishLocal
```

