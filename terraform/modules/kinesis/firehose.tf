# =============================================================================
# Kinesis Firehose -> S3 Data Pipeline
# =============================================================================
#
# Creates:
# - S3 bucket for event storage
# - Kinesis Firehose delivery stream
# - Glue Catalog database and table for Athena queries
# - IAM roles for Firehose
#

# -----------------------------------------------------------------------------
# S3 Bucket for Events
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# S3 Bucket for Events
# -----------------------------------------------------------------------------

resource "aws_s3_bucket" "events" {
  bucket = "${var.project_name}-${var.environment}-events-${data.aws_caller_identity.current.account_id}"

  tags = {
    Name = "${var.project_name}-${var.environment}-events"
  }
}

resource "aws_s3_bucket_versioning" "events" {
  bucket = aws_s3_bucket.events.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "events" {
  bucket = aws_s3_bucket.events.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "events" {
  bucket = aws_s3_bucket.events.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "events" {
  bucket = aws_s3_bucket.events.id

  rule {
    id     = "events-lifecycle"
    status = "Enabled"

    filter {
      prefix = "events/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 90
      storage_class = "GLACIER"
    }

    expiration {
      days = 365
    }
  }

  rule {
    id     = "errors-lifecycle"
    status = "Enabled"

    filter {
      prefix = "errors/"
    }

    expiration {
      days = 30
    }
  }
}

# -----------------------------------------------------------------------------
# Athena Results Bucket
# -----------------------------------------------------------------------------

resource "aws_s3_bucket" "athena_results" {
  bucket = "${var.project_name}-${var.environment}-athena-results-${data.aws_caller_identity.current.account_id}"

  tags = {
    Name = "${var.project_name}-${var.environment}-athena-results"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "athena_results" {
  bucket = aws_s3_bucket.athena_results.id

  rule {
    id     = "cleanup-results"
    status = "Enabled"

    filter {}

    expiration {
      days = 7
    }
  }
}

resource "aws_s3_bucket_public_access_block" "athena_results" {
  bucket = aws_s3_bucket.athena_results.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Glue Catalog Database
# -----------------------------------------------------------------------------

resource "aws_glue_catalog_database" "events" {
  name = "${replace(var.project_name, "-", "_")}_${var.environment}_events"

  description = "Database for rate limiter events"
}

# -----------------------------------------------------------------------------
# Glue Catalog Table
# -----------------------------------------------------------------------------

resource "aws_glue_catalog_table" "rate_limit_events" {
  database_name = aws_glue_catalog_database.events.name
  name          = "rate_limit_events"

  table_type = "EXTERNAL_TABLE"

  parameters = {
    "EXTERNAL"              = "TRUE"
    "parquet.compression"   = "SNAPPY"
    "classification"        = "parquet"
    "projection.enabled"    = "true"
    "projection.year.type"  = "integer"
    "projection.year.range" = "2024,2030"
    "projection.month.type" = "integer"
    "projection.month.range"= "1,12"
    "projection.month.digits"= "2"
    "projection.day.type"   = "integer"
    "projection.day.range"  = "1,31"
    "projection.day.digits" = "2"
    "storage.location.template" = "s3://${aws_s3_bucket.events.bucket}/events/year=$${year}/month=$${month}/day=$${day}/"
  }

  storage_descriptor {
    location      = "s3://${aws_s3_bucket.events.bucket}/events/"
    input_format  = "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat"

    ser_de_info {
      name                  = "ParquetHiveSerDe"
      serialization_library = "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"

      parameters = {
        "serialization.format" = "1"
      }
    }

    columns {
      name    = "event_type"
      type    = "string"
      comment = "Type of event: rate_limit_allowed, rate_limit_rejected, idempotency_hit"
    }

    columns {
      name    = "timestamp"
      type    = "bigint"
      comment = "Event timestamp in milliseconds since epoch"
    }

    columns {
      name    = "api_key"
      type    = "string"
      comment = "API key that made the request"
    }

    columns {
      name    = "request_key"
      type    = "string"
      comment = "Rate limit key (e.g., user:123)"
    }

    columns {
      name    = "endpoint"
      type    = "string"
      comment = "API endpoint that was called"
    }

    columns {
      name    = "allowed"
      type    = "boolean"
      comment = "Whether the request was allowed"
    }

    columns {
      name    = "tokens_remaining"
      type    = "int"
      comment = "Tokens remaining after request (if allowed)"
    }

    columns {
      name    = "cost"
      type    = "int"
      comment = "Token cost of the request"
    }

    columns {
      name    = "retry_after_seconds"
      type    = "int"
      comment = "Seconds until retry (if rejected)"
    }

    columns {
      name    = "reason"
      type    = "string"
      comment = "Rejection reason (if rejected)"
    }

    columns {
      name    = "idempotency_key"
      type    = "string"
      comment = "Idempotency key (for idempotency events)"
    }
  }

  partition_keys {
    name = "year"
    type = "string"
  }

  partition_keys {
    name = "month"
    type = "string"
  }

  partition_keys {
    name = "day"
    type = "string"
  }
}

# -----------------------------------------------------------------------------
# Kinesis Firehose Delivery Stream
# -----------------------------------------------------------------------------

resource "aws_kinesis_firehose_delivery_stream" "events" {
  name        = "${var.project_name}-${var.environment}-events-delivery"
  destination = "extended_s3"

  kinesis_source_configuration {
    kinesis_stream_arn = aws_kinesis_stream.events.arn
    role_arn           = aws_iam_role.firehose.arn
  }

  extended_s3_configuration {
    role_arn            = aws_iam_role.firehose.arn
    bucket_arn          = aws_s3_bucket.events.arn
    prefix              = "events/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"
    error_output_prefix = "errors/!{firehose:error-output-type}/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"

    buffering_size     = var.firehose_buffer_size
    buffering_interval = var.firehose_buffer_interval

    compression_format = "UNCOMPRESSED" # Parquet handles compression

    # Convert JSON to Parquet
    data_format_conversion_configuration {
      input_format_configuration {
        deserializer {
          open_x_json_ser_de {}
        }
      }

      output_format_configuration {
        serializer {
          parquet_ser_de {
            compression = "SNAPPY"
          }
        }
      }

      schema_configuration {
        database_name = aws_glue_catalog_database.events.name
        table_name    = aws_glue_catalog_table.rate_limit_events.name
        role_arn      = aws_iam_role.firehose.arn
        region        = data.aws_region.current.name
      }
    }

    cloudwatch_logging_options {
      enabled         = true
      log_group_name  = aws_cloudwatch_log_group.firehose.name
      log_stream_name = aws_cloudwatch_log_stream.firehose.name
    }
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-events-delivery"
  }
}

# -----------------------------------------------------------------------------
# CloudWatch Log Group for Firehose
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "firehose" {
  name              = "/aws/kinesisfirehose/${var.project_name}-${var.environment}-events"
  retention_in_days = 14

  tags = {
    Name = "${var.project_name}-${var.environment}-firehose-logs"
  }
}

resource "aws_cloudwatch_log_stream" "firehose" {
  name           = "delivery-stream"
  log_group_name = aws_cloudwatch_log_group.firehose.name
}

# -----------------------------------------------------------------------------
# IAM Role for Firehose
# -----------------------------------------------------------------------------

resource "aws_iam_role" "firehose" {
  name = "${var.project_name}-${var.environment}-firehose"

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

  tags = {
    Name = "${var.project_name}-${var.environment}-firehose"
  }
}

resource "aws_iam_role_policy" "firehose" {
  name = "firehose-permissions"
  role = aws_iam_role.firehose.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3Access"
        Effect = "Allow"
        Action = [
          "s3:AbortMultipartUpload",
          "s3:GetBucketLocation",
          "s3:GetObject",
          "s3:ListBucket",
          "s3:ListBucketMultipartUploads",
          "s3:PutObject"
        ]
        Resource = [
          aws_s3_bucket.events.arn,
          "${aws_s3_bucket.events.arn}/*"
        ]
      },
      {
        Sid    = "KinesisAccess"
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
        Sid    = "GlueAccess"
        Effect = "Allow"
        Action = [
          "glue:GetTable",
          "glue:GetTableVersion",
          "glue:GetTableVersions"
        ]
        Resource = [
          "arn:aws:glue:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:catalog",
          "arn:aws:glue:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:database/${aws_glue_catalog_database.events.name}",
          "arn:aws:glue:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/${aws_glue_catalog_database.events.name}/*"
        ]
      },
      {
        Sid    = "CloudWatchLogsAccess"
        Effect = "Allow"
        Action = [
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.firehose.arn}:*"
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# Athena Workgroup
# -----------------------------------------------------------------------------

resource "aws_athena_workgroup" "main" {
  name = "${var.project_name}-${var.environment}"

  configuration {
    enforce_workgroup_configuration    = true
    publish_cloudwatch_metrics_enabled = true

    result_configuration {
      output_location = "s3://${aws_s3_bucket.athena_results.bucket}/results/"

      encryption_configuration {
        encryption_option = "SSE_S3"
      }
    }
  }

  tags = {
    Name = "${var.project_name}-${var.environment}"
  }
}

# -----------------------------------------------------------------------------
# Data Sources
# -----------------------------------------------------------------------------

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
