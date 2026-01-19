# =============================================================================
# Terraform Configuration for Rate Limiter Platform
# =============================================================================
#
# This configuration deploys:
# - VPC with public/private subnets
# - DynamoDB tables for rate limiting and idempotency
# - Kinesis stream for event publishing
# - ECS Fargate cluster with ALB
# - CloudWatch dashboards and alarms
#

terraform {
  required_version = ">= 1.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Uncomment and configure for remote state
  # backend "s3" {
  #   bucket         = "your-terraform-state-bucket"
  #   key            = "rate-limiter/terraform.tfstate"
  #   region         = "us-east-1"
  #   encrypt        = true
  #   dynamodb_table = "terraform-locks"
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# =============================================================================
# Data Sources
# =============================================================================

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# =============================================================================
# Modules
# =============================================================================

module "networking" {
  source = "./modules/networking"

  project_name = var.project_name
  environment  = var.environment
  vpc_cidr     = var.vpc_cidr
}

module "dynamodb" {
  source = "./modules/dynamodb"

  project_name = var.project_name
  environment  = var.environment
}

module "kinesis" {
  source = "./modules/kinesis"

  project_name    = var.project_name
  environment     = var.environment
  shard_count     = var.kinesis_shard_count
  retention_hours = var.kinesis_retention_hours
}

module "ecs" {
  source = "./modules/ecs"

  project_name       = var.project_name
  environment        = var.environment
  vpc_id             = module.networking.vpc_id
  private_subnet_ids = module.networking.private_subnet_ids
  public_subnet_ids  = module.networking.public_subnet_ids

  container_image = var.container_image
  container_port  = 8080
  desired_count   = var.desired_count
  cpu             = var.ecs_cpu
  memory          = var.ecs_memory

  # Pass resource names to ECS
  rate_limit_table  = module.dynamodb.rate_limit_table_name
  idempotency_table = module.dynamodb.idempotency_table_name
  kinesis_stream    = module.kinesis.stream_name

  # Pass ARNs for IAM policies
  rate_limit_table_arn  = module.dynamodb.rate_limit_table_arn
  idempotency_table_arn = module.dynamodb.idempotency_table_arn
  kinesis_stream_arn    = module.kinesis.stream_arn
}

# =============================================================================
# CloudWatch Dashboard
# =============================================================================

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.project_name}-${var.environment}"

  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", module.ecs.cluster_name, "ServiceName", module.ecs.service_name, { stat = "Average" }],
            [".", "MemoryUtilization", ".", ".", ".", ".", { stat = "Average" }]
          ]
          period = 300
          region = var.aws_region
          title  = "ECS CPU & Memory Utilization"
          view   = "timeSeries"
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", module.ecs.alb_arn_suffix, { stat = "Sum" }],
            [".", "HTTPCode_Target_2XX_Count", ".", ".", { stat = "Sum" }],
            [".", "HTTPCode_Target_4XX_Count", ".", ".", { stat = "Sum" }],
            [".", "HTTPCode_Target_5XX_Count", ".", ".", { stat = "Sum" }]
          ]
          period = 300
          region = var.aws_region
          title  = "ALB Request Metrics"
          view   = "timeSeries"
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", module.ecs.alb_arn_suffix, { stat = "Average" }],
            ["...", { stat = "p99" }]
          ]
          period = 300
          region = var.aws_region
          title  = "ALB Response Time"
          view   = "timeSeries"
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          metrics = [
            ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", module.dynamodb.rate_limit_table_name, { stat = "Sum" }],
            [".", "ConsumedWriteCapacityUnits", ".", ".", { stat = "Sum" }]
          ]
          period = 300
          region = var.aws_region
          title  = "DynamoDB Capacity (Rate Limits)"
          view   = "timeSeries"
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 12
        height = 6
        properties = {
          metrics = [
            ["AWS/Kinesis", "IncomingRecords", "StreamName", module.kinesis.stream_name, { stat = "Sum" }],
            [".", "IncomingBytes", ".", ".", { stat = "Sum" }]
          ]
          period = 300
          region = var.aws_region
          title  = "Kinesis Incoming Data"
          view   = "timeSeries"
        }
      }
    ]
  })
}

# =============================================================================
# CloudWatch Alarms
# =============================================================================

resource "aws_cloudwatch_metric_alarm" "high_cpu" {
  alarm_name          = "${var.project_name}-${var.environment}-high-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "CPU utilization is above 80%"

  dimensions = {
    ClusterName = module.ecs.cluster_name
    ServiceName = module.ecs.service_name
  }

  alarm_actions = var.alarm_sns_topic_arn != "" ? [var.alarm_sns_topic_arn] : []
}

resource "aws_cloudwatch_metric_alarm" "high_memory" {
  alarm_name          = "${var.project_name}-${var.environment}-high-memory"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "MemoryUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Memory utilization is above 80%"

  dimensions = {
    ClusterName = module.ecs.cluster_name
    ServiceName = module.ecs.service_name
  }

  alarm_actions = var.alarm_sns_topic_arn != "" ? [var.alarm_sns_topic_arn] : []
}

resource "aws_cloudwatch_metric_alarm" "high_5xx" {
  alarm_name          = "${var.project_name}-${var.environment}-high-5xx"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HTTPCode_Target_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = 300
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "High number of 5XX errors"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = module.ecs.alb_arn_suffix
  }

  alarm_actions = var.alarm_sns_topic_arn != "" ? [var.alarm_sns_topic_arn] : []
}
