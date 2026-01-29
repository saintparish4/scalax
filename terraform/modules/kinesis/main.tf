# Kinesis module - Event streaming and analytics pipeline

# Kinesis Data Stream
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
    Name    = "${var.project_name}-${var.environment}-events"
    Purpose = "Rate limit event streaming"
  }
}

# S3 bucket for Firehose delivery (only if enabled)
resource "aws_s3_bucket" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = var.s3_bucket_name != "" ? var.s3_bucket_name : "${var.project_name}-${var.environment}-events-${data.aws_caller_identity.current.account_id}"

  tags = {
    Name    = "${var.project_name}-${var.environment}-events"
    Purpose = "Event storage for analytics"
  }
}

resource "aws_s3_bucket_versioning" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = aws_s3_bucket.events[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = aws_s3_bucket.events[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = aws_s3_bucket.events[0].id

  rule {
    id     = "archive"
    status = "Enabled"

    transition {
      days          = 30
      storage_class = "GLACIER"
    }

    expiration {
      days = 90
    }

    filter {
      prefix = "events/"
    }
  }
}

# Firehose IAM Role
resource "aws_iam_role" "firehose" {
  count = var.enable_firehose ? 1 : 0
  name  = "${var.project_name}-${var.environment}-firehose"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "firehose.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy" "firehose" {
  count = var.enable_firehose ? 1 : 0
  name  = "firehose-permissions"
  role  = aws_iam_role.firehose[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetBucketLocation",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.events[0].arn,
          "${aws_s3_bucket.events[0].arn}/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "kinesis:DescribeStream",
          "kinesis:GetShardIterator",
          "kinesis:GetRecords",
          "kinesis:ListShards"
        ]
        Resource = aws_kinesis_stream.events.arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:PutLogEvents"
        ]
        Resource = "*"
      }
    ]
  })
}

# Kinesis Firehose Delivery Stream
resource "aws_kinesis_firehose_delivery_stream" "events" {
  count       = var.enable_firehose ? 1 : 0
  name        = "${var.project_name}-${var.environment}-events-firehose"
  destination = "extended_s3"

  kinesis_source_configuration {
    kinesis_stream_arn = aws_kinesis_stream.events.arn
    role_arn           = aws_iam_role.firehose[0].arn
  }

  extended_s3_configuration {
    role_arn   = aws_iam_role.firehose[0].arn
    bucket_arn = aws_s3_bucket.events[0].arn
    prefix     = "events/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"

    buffering_size     = 5   # MB
    buffering_interval = 300 # seconds

    compression_format = "GZIP"

    cloudwatch_logging_options {
      enabled         = true
      log_group_name  = "/aws/firehose/${var.project_name}-${var.environment}"
      log_stream_name = "events"
    }
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-events-firehose"
    Purpose = "Event delivery to S3"
  }
}

data "aws_caller_identity" "current" {}