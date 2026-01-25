output "stream_name" {
  description = "Name of the Kinesis stream"
  value       = aws_kinesis_stream.events.name
}

output "stream_arn" {
  description = "ARN of the Kinesis stream"
  value       = aws_kinesis_stream.events.arn
}

# Firehose outputs
output "firehose_delivery_stream_name" {
  description = "Name of the Firehose delivery stream"
  value       = aws_kinesis_firehose_delivery_stream.events.name
}

output "firehose_delivery_stream_arn" {
  description = "ARN of the Firehose delivery stream"
  value       = aws_kinesis_firehose_delivery_stream.events.arn
}

# S3 outputs
output "events_bucket_name" {
  description = "Name of the S3 bucket for events"
  value       = aws_s3_bucket.events.bucket
}

output "events_bucket_arn" {
  description = "ARN of the S3 bucket for events"
  value       = aws_s3_bucket.events.arn
}

output "athena_results_bucket_name" {
  description = "Name of the S3 bucket for Athena results"
  value       = aws_s3_bucket.athena_results.bucket
}

# Glue outputs
output "glue_database_name" {
  description = "Name of the Glue catalog database"
  value       = aws_glue_catalog_database.events.name
}

output "glue_table_name" {
  description = "Name of the Glue catalog table"
  value       = aws_glue_catalog_table.rate_limit_events.name
}

# Athena outputs
output "athena_workgroup_name" {
  description = "Name of the Athena workgroup"
  value       = aws_athena_workgroup.main.name
}