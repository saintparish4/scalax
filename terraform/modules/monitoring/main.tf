# Monitoring module - CloudWatch dashboards and alarms

locals {
  namespace = "RateLimiter/${var.environment}"
}

# CloudWatch Dashboard
resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.project_name}-${var.environment}"

  dashboard_body = jsonencode({
    widgets = [
      # ECS Metrics Row
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", var.ecs_service_name],
            [".", "MemoryUtilization", ".", ".", ".", "."]
          ]
          period = 300
          stat   = "Average"
          region = data.aws_region.current.name
          title  = "ECS Service Metrics"
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
            ["AWS/ECS", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", var.ecs_service_name]
          ]
          period = 60
          stat   = "Average"
          region = data.aws_region.current.name
          title  = "Running Tasks"
        }
      },
      # Rate Limit Metrics Row
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 8
        height = 6
        properties = {
          metrics = [
            [local.namespace, "RateLimitAllowed", { stat = "Sum" }],
            [".", "RateLimitRejected", { stat = "Sum" }]
          ]
          period = 60
          region = data.aws_region.current.name
          title  = "Rate Limit Decisions"
          view   = "timeSeries"
        }
      },
      {
        type   = "metric"
        x      = 8
        y      = 6
        width  = 8
        height = 6
        properties = {
          metrics = [
            [local.namespace, "rate_limit_check", { stat = "p50" }],
            ["...", { stat = "p99" }]
          ]
          period = 60
          region = data.aws_region.current.name
          title  = "Rate Limit Latency (ms)"
        }
      },
      {
        type   = "metric"
        x      = 16
        y      = 6
        width  = 8
        height = 6
        properties = {
          metrics = [
            [local.namespace, "DegradedOperation", { stat = "Sum" }]
          ]
          period = 60
          region = data.aws_region.current.name
          title  = "Degraded Operations"
        }
      },
      # ALB Metrics Row
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 8
        height = 6
        properties = {
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix, { stat = "Sum" }]
          ]
          period = 60
          region = data.aws_region.current.name
          title  = "ALB Request Count"
        }
      },
      {
        type   = "metric"
        x      = 8
        y      = 12
        width  = 8
        height = 6
        properties = {
          metrics = [
            ["AWS/ApplicationELB", "HTTPCode_Target_2XX_Count", "LoadBalancer", var.alb_arn_suffix],
            [".", "HTTPCode_Target_4XX_Count", ".", "."],
            [".", "HTTPCode_Target_5XX_Count", ".", "."]
          ]
          period = 60
          stat   = "Sum"
          region = data.aws_region.current.name
          title  = "ALB Response Codes"
        }
      },
      {
        type   = "metric"
        x      = 16
        y      = 12
        width  = 8
        height = 6
        properties = {
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p50" }],
            ["...", { stat = "p99" }]
          ]
          period = 60
          region = data.aws_region.current.name
          title  = "ALB Response Time"
        }
      },
      # Circuit Breaker and Cache Row
      {
        type   = "metric"
        x      = 0
        y      = 18
        width  = 12
        height = 6
        properties = {
          metrics = [
            [local.namespace, "CircuitBreakerState", "CircuitBreaker", "dynamodb"],
            [".", ".", ".", "kinesis"]
          ]
          period = 60
          stat   = "Average"
          region = data.aws_region.current.name
          title  = "Circuit Breaker State (0=Closed, 0.5=HalfOpen, 1=Open)"
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 18
        width  = 12
        height = 6
        properties = {
          metrics = [
            [local.namespace, "CacheHitRate", "CacheName", "rate_limit_status", { stat = "Average" }]
          ]
          period = 60
          region = data.aws_region.current.name
          title  = "Cache Hit Rate (%)"
        }
      }
    ]
  })
}

# CloudWatch Alarms
resource "aws_cloudwatch_metric_alarm" "high_error_rate" {
  count               = var.alarm_sns_topic_arn != "" ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-high-error-rate"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 1

  metric_query {
    id          = "error_rate"
    expression  = "errors / requests * 100"
    label       = "Error Rate %"
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = 300
      stat        = "Sum"
      dimensions = {
        LoadBalancer = var.alb_arn_suffix
      }
    }
  }

  metric_query {
    id = "requests"
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      period      = 300
      stat        = "Sum"
      dimensions = {
        LoadBalancer = var.alb_arn_suffix
      }
    }
  }

  alarm_description = "Error rate exceeded 1%"
  alarm_actions     = [var.alarm_sns_topic_arn]
  ok_actions        = [var.alarm_sns_topic_arn]

  tags = {
    Name = "${var.project_name}-${var.environment}-high-error-rate"
  }
}

resource "aws_cloudwatch_metric_alarm" "high_latency" {
  count               = var.alarm_sns_topic_arn != "" ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-high-latency"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "TargetResponseTime"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  extended_statistic  = "p99"
  threshold           = 0.1 # 100ms
  
  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  alarm_description = "P99 latency exceeded 100ms"
  alarm_actions     = [var.alarm_sns_topic_arn]
  ok_actions        = [var.alarm_sns_topic_arn]

  tags = {
    Name = "${var.project_name}-${var.environment}-high-latency"
  }
}

resource "aws_cloudwatch_metric_alarm" "low_healthy_hosts" {
  count               = var.alarm_sns_topic_arn != "" ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-low-healthy-hosts"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Minimum"
  threshold           = 2
  
  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  alarm_description = "Less than 2 healthy hosts"
  alarm_actions     = [var.alarm_sns_topic_arn]
  ok_actions        = [var.alarm_sns_topic_arn]

  tags = {
    Name = "${var.project_name}-${var.environment}-low-healthy-hosts"
  }
}

resource "aws_cloudwatch_metric_alarm" "circuit_breaker_open" {
  count               = var.alarm_sns_topic_arn != "" ? 1 : 0
  alarm_name          = "${var.project_name}-${var.environment}-circuit-breaker-open"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "CircuitBreakerState"
  namespace           = local.namespace
  period              = 60
  statistic           = "Maximum"
  threshold           = 1 # 1 = Open

  alarm_description = "Circuit breaker is open"
  alarm_actions     = [var.alarm_sns_topic_arn]
  ok_actions        = [var.alarm_sns_topic_arn]

  tags = {
    Name = "${var.project_name}-${var.environment}-circuit-breaker-open"
  }
}

data "aws_region" "current" {}

# Outputs
output "dashboard_url" {
  value = "https://${data.aws_region.current.name}.console.aws.amazon.com/cloudwatch/home?region=${data.aws_region.current.name}#dashboards:name=${aws_cloudwatch_dashboard.main.dashboard_name}"
}
