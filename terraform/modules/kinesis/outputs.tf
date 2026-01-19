output "stream_name" {
  description = "Name of the Kinesis stream"
  value       = aws_kinesis_stream.events.name
}

output "stream_arn" {
  description = "ARN of the Kinesis stream"
  value       = aws_kinesis_stream.events.arn
}
