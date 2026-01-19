# =============================================================================
# Outputs for Rate Limiter Platform
# =============================================================================

# -----------------------------------------------------------------------------
# Networking
# -----------------------------------------------------------------------------

output "vpc_id" {
  description = "ID of the VPC"
  value       = module.networking.vpc_id
}

output "public_subnet_ids" {
  description = "IDs of public subnets"
  value       = module.networking.public_subnet_ids
}

output "private_subnet_ids" {
  description = "IDs of private subnets"
  value       = module.networking.private_subnet_ids
}

# -----------------------------------------------------------------------------
# Application
# -----------------------------------------------------------------------------

output "alb_dns_name" {
  description = "DNS name of the Application Load Balancer"
  value       = module.ecs.alb_dns_name
}

output "api_endpoint" {
  description = "API endpoint URL"
  value       = "http://${module.ecs.alb_dns_name}"
}

# -----------------------------------------------------------------------------
# ECS
# -----------------------------------------------------------------------------

output "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  value       = module.ecs.cluster_name
}

output "ecs_service_name" {
  description = "Name of the ECS service"
  value       = module.ecs.service_name
}

# -----------------------------------------------------------------------------
# DynamoDB
# -----------------------------------------------------------------------------

output "rate_limit_table_name" {
  description = "Name of the rate limits DynamoDB table"
  value       = module.dynamodb.rate_limit_table_name
}

output "idempotency_table_name" {
  description = "Name of the idempotency DynamoDB table"
  value       = module.dynamodb.idempotency_table_name
}

# -----------------------------------------------------------------------------
# Kinesis
# -----------------------------------------------------------------------------

output "kinesis_stream_name" {
  description = "Name of the Kinesis stream"
  value       = module.kinesis.stream_name
}

# -----------------------------------------------------------------------------
# CloudWatch
# -----------------------------------------------------------------------------

output "cloudwatch_dashboard_url" {
  description = "URL to the CloudWatch dashboard"
  value       = "https://${var.aws_region}.console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${var.project_name}-${var.environment}"
}

output "cloudwatch_log_group" {
  description = "CloudWatch Log Group for application logs"
  value       = module.ecs.log_group_name
}
