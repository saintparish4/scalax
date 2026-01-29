# =============================================================================
# Demo Environment Configuration
# Quick-deploy configuration for interviews and demos
# =============================================================================

environment = "demo"

# AWS Configuration
aws_region = "us-east-1"

# ECS Configuration (minimal for cost savings)
ecs_desired_count = 1
ecs_cpu           = 256
ecs_memory        = 512
enable_autoscaling = false
ecs_min_capacity  = 1
ecs_max_capacity  = 1

# DynamoDB Configuration (on-demand for demo)
dynamodb_billing_mode = "PAY_PER_REQUEST"

# Kinesis Configuration (minimal)
kinesis_shard_count     = 1
kinesis_retention_hours = 24
enable_kinesis_firehose = false

# Networking (minimal for demo)
availability_zones = ["us-east-1a"]

# Container image - set via -var flag in deploy script
# container_image = "123456789.dkr.ecr.us-east-1.amazonaws.com/rate-limiter:latest"
