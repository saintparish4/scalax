# Scala Distributed Rate Limiter Platform

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Scala](https://img.shields.io/badge/scala-3.7.4-red.svg)](https://www.scala-lang.org/)

Distributed rate limiting and idempotency platform built with Scala 3 and deployed on AWS. Demonstrates advanced backend engineering, functional programming, and cloud-native architecture.

**Note:** AWS deployment and performance have not been tested; the stack is designed for AWS but validated locally (e.g. LocalStack/Docker).

## Architecture

```
Internet → ALB → ECS Fargate → DynamoDB (state)
                              → Kinesis → S3 → Athena
                              → CloudWatch (metrics/logs)
```

**Key Components:**
- **HTTP4s API** - RESTful endpoints for rate limiting and idempotency
- **Token Bucket Engine** - Industry-standard rate limiting algorithm
- **DynamoDB Storage** - Distributed state with atomic operations
- **Kinesis Streaming** - Real-time event pipeline for analytics
- **ECS Fargate** - Serverless container orchestration

See [Architecture Documentation](docs/ARCHITECTURE.md) for detailed design decisions.

## Product Vision

This platform provides a scalable, high-performance rate limiting service that enables applications to:
- **Control API usage** with configurable rate limits using token bucket algorithm
- **Ensure idempotency** for critical operations with first-writer-wins semantics
- **Stream events** to AWS Kinesis for real-time analytics and monitoring
- **Scale horizontally** to handle high-throughput workloads (AWS performance not yet tested)

The platform offers distributed rate limiting that works seamlessly across multiple service instances while maintaining consistency and performance.

## Quick Start

### Prerequisites

- Scala 3.7.4+, SBT 1.9+
- Docker 20.10+ (for local development)
- AWS CLI 2.x (optional, for future AWS deployment)
- Terraform 1.5+ (optional, for future AWS deployment)

## Documentation

- **[API Reference](docs/API.md)** - Complete API documentation with examples
- **[Architecture Decisions](docs/ARCHITECTURE.md)** - Design rationale and trade-offs

**Note:** Deployment and operational runbooks are not yet available as AWS deployment has not been tested.

## Features

### Rate Limiting

- **Token Bucket Algorithm** - Implemented algorithm with burst handling
- **Configurable Limits** - Per-user, per-API-key, per-endpoint limits
- **Low-latency design** - Token bucket and DynamoDB tuned for fast checks (AWS not yet tested)
- **Horizontal Scaling** - Designed to scale with ECS and DynamoDB (AWS not yet tested)

**Note:** Sliding window algorithm is not yet implemented.

### Idempotency

- **First-Writer-Wins** - Atomic operations using DynamoDB conditional writes
- **Response Caching** - Store and replay responses for duplicate requests
- **TTL-Based Cleanup** - Automatic expiration of old keys
- **Safe Retries** - Clients can safely retry failed requests

### Observability

- **Structured Logging** - JSON logs with correlation IDs
- **Health Endpoints** - `/health` and `/ready` endpoints for monitoring
- **Custom Metrics** - CloudWatch metrics for rate limit decisions (when deployed to AWS, not yet tested)

**Note:** Distributed tracing (X-Ray) and CloudWatch dashboards are planned but not yet implemented/tested.

### Analytics

- **Event Streaming** - Kinesis pipeline for real-time events (when enabled, AWS not yet tested)

**Note:** S3 data lake, Athena queries, and analytics dashboards are planned but not yet implemented/tested.

## Technology Stack

**Core:**
- Scala 3.7.4
- Cats Effect 3.6.3 (functional effects)
- HTTP4s 0.23.32 (HTTP server/client)
- Circe 0.14.15 (JSON serialization)
- PureConfig 0.17.9 (configuration management)
- Log4Cats 2.7.1 (structured logging)

**AWS Services:**
- ECS Fargate (container orchestration)
- DynamoDB (NoSQL state storage)
- Kinesis (event streaming)
- CloudWatch (observability)
- Application Load Balancer (traffic distribution)
- S3 + Athena (analytics)

**Infrastructure:**
- Terraform (IaC - AWS deployment not yet tested)
- Docker (containerization)
- LocalStack (local AWS emulation)

### Local Development

```bash
# Clone repository
git clone https://github.com/your-org/scala-rate-limiter.git
cd scala-rate-limiter

# Start local environment (LocalStack)
docker-compose up -d

# Run application
sbt run

# Test endpoints
curl http://localhost:8080/health
curl -X POST http://localhost:8080/v1/ratelimit/check \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-api-key" \
  -d '{"key": "user:123", "cost": 1}'
```

### Load Testing

```bash
# Run load tests against local instance
./scripts/load-test.sh dev baseline

# Run quick load tests against local instance
./scripts/load-test.sh dev quick

# For more options, see the script help
./scripts/load-test.sh --help
```

**Note:** AWS deployment instructions are not yet available as deployment has not been tested. Terraform configuration exists but requires validation.

## Performance

Performance and scalability on AWS have not been tested. The design targets low-latency rate-limit checks and horizontal scaling via ECS and DynamoDB; run your own load tests when deploying to AWS.

## API Examples

### Check Rate Limit

```bash
curl -X POST http://localhost:8080/v1/ratelimit/check \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-api-key" \
  -d '{
    "key": "user:12345",
    "cost": 1
  }'
```

**Note:** Use `test-api-key`, `admin-api-key`, or `free-api-key` for local development. The `algorithm` field is accepted but not currently used (only token bucket is implemented).

**Response (Allowed):**
```json
{
  "allowed": true,
  "tokensRemaining": 95,
  "limit": 100,
  "resetAt": "2024-01-15T10:30:00Z",
  "retryAfter": null
}
```

**Response (Rate Limited - 429):**
```json
{
  "allowed": false,
  "tokensRemaining": null,
  "limit": 100,
  "resetAt": "2024-01-15T10:30:00Z",
  "retryAfter": 5,
  "message": "Rate limit exceeded"
}
```

### Check Idempotency

```bash
curl -X POST http://localhost:8080/v1/idempotency/check \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-api-key" \
  -d '{
    "idempotencyKey": "payment:abc-123",
    "ttl": 86400
  }'
```

### Complete Idempotency Operation

```bash
curl -X POST http://localhost:8080/v1/idempotency/payment:abc-123/complete \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-api-key" \
  -d '{
    "statusCode": 201,
    "body": "{\"paymentId\": \"pay_xyz789\"}",
    "headers": {"Content-Type": "application/json"}
  }'
```

See [API Documentation](docs/API.md) for complete reference.

## Testing

```bash
# Unit tests
sbt test

# Integration tests (requires Docker)
sbt it:test

# Test coverage
sbt clean coverage test coverageReport

# Load testing
./scripts/load-test.sh
```

## Project Structure

```
scala-rate-limiter-platform/
├── src/
│   ├── main/scala/com/ratelimiter/
│   │   ├── Main.scala                 # Application entry point
│   │   ├── api/                       # HTTP routes and handlers
│   │   ├── core/                      # Core rate limiting logic
│   │   ├── storage/                   # DynamoDB implementations
│   │   ├── events/                    # Kinesis event publishing
│   │   ├── metrics/                   # CloudWatch metrics
│   │   └── config/                    # Configuration
│   └── test/scala/                    # Tests
├── terraform/
│   ├── modules/                       # Reusable Terraform modules
│   │   ├── ecs/                       # ECS Fargate
│   │   ├── dynamodb/                  # DynamoDB tables
│   │   ├── kinesis/                   # Kinesis streams
│   │   └── networking/                # VPC, subnets, security groups
│   └── environments/                  # Environment-specific configs
│       ├── dev/
│       ├── staging/
│       └── prod/
├── docs/                              # Documentation (API, Architecture)
├── scripts/                            # Utility scripts (load-test.sh)
└── docker-compose.yml                 # Local development setup
```

## Security

- **Authentication:** API key-based authentication (implemented)
- **Rate Limiting:** Built-in rate limiting for authentication attempts

**Note:** AWS-specific security features (IAM roles, VPC, Secrets Manager, CloudWatch Logs) are planned but not yet tested. See [Architecture Documentation](docs/ARCHITECTURE.md) for security design.

## Monitoring & Alerting

**CloudWatch (when deployed to AWS):**
- Alarms for high error rate, high latency, DynamoDB throttles, and low ECS task count
- Dashboards for request rate, latency, rate-limit decisions, DynamoDB, and ECS health

AWS monitoring has not been tested. Operational runbooks are not yet available.

## CI/CD Pipeline

**Note:** CI/CD is not implemented yet; it will be added soon.

**Planned (GitHub Actions):**
1. **Pull Request** → Run tests, lint, security scan
2. **Merge to `develop`** → Auto-deploy to dev environment
3. **Tag `v*.*.*`** → Auto-deploy to production

**Planned deployment process:**
- Build Docker image
- Push to ECR
- Update ECS service
- Run smoke tests
- Notify Slack

**Note:** Deployment guides are not yet available as AWS deployment has not been tested.

## Design Highlights

### Functional Programming

```scala
// Pure, composable rate limiting
def checkRateLimit[F[_]: Async](
  key: String,
  cost: Int
): F[RateLimitDecision] =
  for
    state <- store.get(key)
    refilled = refillTokens(state, Clock[F].realTime)
    decision <- 
      if refilled.tokens >= cost then
        store.put(refilled.consume(cost))
          .as(Allowed(refilled.tokens - cost))
      else
        Async[F].pure(Rejected(calculateRetryAfter(refilled)))
  yield decision
```

### Resource Safety

```scala
// Automatic cleanup with Resource
val resources: Resource[IO, (DynamoClient, KinesisClient)] =
  for
    dynamo <- DynamoClient.resource[IO](config)
    kinesis <- KinesisClient.resource[IO](config)
  yield (dynamo, kinesis)

resources.use { case (dynamo, kinesis) =>
  // Application runs here
  // Resources automatically closed on shutdown/error
}
```

### Observability

```scala
// Structured logging with context
logger.info(
  "Rate limit check completed",
  Map(
    "requestId" -> requestId,
    "apiKey" -> apiKey,
    "allowed" -> allowed,
    "tokensRemaining" -> tokensRemaining,
    "latencyMs" -> latencyMs
  )
)
```

## Contributing

Feedback is welcome!

1. Open an issue to discuss proposed changes
2. Fork the repository
3. Create a feature branch
4. Submit a pull request
