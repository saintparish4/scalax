# =============================================================================
# Kinesis Module
# =============================================================================
#
# Creates:
# - Kinesis Data Stream for rate limit events
#

resource "aws_kinesis_stream" "events" {
  name             = "${var.project_name}-${var.environment}-events"
  shard_count      = var.shard_count
  retention_period = var.retention_hours

  stream_mode_details {
    stream_mode = "PROVISIONED"
  }

  encryption_type = "KMS"
  kms_key_id      = "alias/aws/kinesis"

  tags = {
    Name = "${var.project_name}-${var.environment}-events"
  }
}

# CloudWatch alarms for Kinesis
resource "aws_cloudwatch_metric_alarm" "iterator_age" {
  alarm_name          = "${var.project_name}-${var.environment}-kinesis-iterator-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "GetRecords.IteratorAgeMilliseconds"
  namespace           = "AWS/Kinesis"
  period              = 300
  statistic           = "Maximum"
  threshold           = 60000 # 1 minute
  alarm_description   = "Kinesis iterator age is too high (consumer is falling behind)"
  treat_missing_data  = "notBreaching"

  dimensions = {
    StreamName = aws_kinesis_stream.events.name
  }
}