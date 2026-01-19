output "rate_limit_table_name" {
  description = "Name of the rate limits table"
  value       = aws_dynamodb_table.rate_limits.name
}

output "rate_limit_table_arn" {
  description = "ARN of the rate limits table"
  value       = aws_dynamodb_table.rate_limits.arn
}

output "idempotency_table_name" {
  description = "Name of the idempotency table"
  value       = aws_dynamodb_table.idempotency.name
}

output "idempotency_table_arn" {
  description = "ARN of the idempotency table"
  value       = aws_dynamodb_table.idempotency.arn
}
