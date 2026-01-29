## Architecture Overview

**Note:** This document describes the intended architecture. AWS deployment has not been tested. Currently only local development with docker-compose (LocalStack) is validated.

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                        API Clients                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Application Load Balancer                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Rate Limiter Service (ECS/Lambda)               │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  HTTP API    │  │  Rate Limit  │  │ Idempotency  │      │
│  │  (HTTP4s)    │  │  Engine      │  │   Store      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐
│   CloudWatch    │  │    DynamoDB      │  │    Kinesis      │
│   (Metrics)     │  │  (State Store)   │  │  (Events)       │
└─────────────────┘  └──────────────────┘  └─────────────────┘
                                                    │
                                                    ▼
                                          ┌──────────────────┐
                                          │  Data Analytics  │
                                          │  (S3 + Athena)   │
                                          └──────────────────┘
```
