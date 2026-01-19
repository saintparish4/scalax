# =============================================================================
# DynamoDB Module
# =============================================================================
#
# Creates:
# - Rate limits table (token bucket state)
# - Idempotency table (request deduplication)
#
# Both tables use:
# - PAY_PER_REQUEST billing (auto-scaling)
# - TTL for automatic cleanup
# - Point-in-time recovery (rate limits only)
#

# -----------------------------------------------------------------------------
# Rate Limits Table
# -----------------------------------------------------------------------------

resource "aws_dynamodb_table" "rate_limits" {
  name         = "${var.project_name}-${var.environment}-rate-limits"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = {
    Name = "${var.project_name}-${var.environment}-rate-limits"
  }
}

# -----------------------------------------------------------------------------
# Idempotency Table
# -----------------------------------------------------------------------------

resource "aws_dynamodb_table" "idempotency" {
  name         = "${var.project_name}-${var.environment}-idempotency"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  # Point-in-time recovery not needed for idempotency (ephemeral data)

  tags = {
    Name = "${var.project_name}-${var.environment}-idempotency"
  }
}
