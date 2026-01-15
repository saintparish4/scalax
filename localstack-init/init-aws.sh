#!/bin/bash
set -e

echo "============================================"
echo "Initializing LocalStack resources..."
echo "============================================"

# Wait for LocalStack to be ready
echo "Waiting for LocalStack services..."
sleep 2

# Function to check if DynamoDB table exists
table_exists() {
    awslocal dynamodb describe-table --table-name "$1" >/dev/null 2>&1
}

# Function to check if Kinesis stream exists
stream_exists() {
    awslocal kinesis describe-stream --stream-name "$1" >/dev/null 2>&1
}

# Create DynamoDB table: rate-limits
if table_exists "rate-limits"; then
    echo "DynamoDB table 'rate-limits' already exists, skipping creation"
else
    echo "Creating DynamoDB table: rate-limits"
    awslocal dynamodb create-table \
      --table-name rate-limits \
      --attribute-definitions AttributeName=pk,AttributeType=S \
      --key-schema AttributeName=pk,KeyType=HASH \
      --billing-mode PAY_PER_REQUEST \
      --tags Key=Environment,Value=local
    echo "Waiting for table 'rate-limits' to become active..."
    awslocal dynamodb wait table-exists --table-name rate-limits
fi

# Enable TTL on rate-limits table
echo "Enabling TTL on rate-limits table"
awslocal dynamodb update-time-to-live \
  --table-name rate-limits \
  --time-to-live-specification Enabled=true,AttributeName=ttl || echo "Note: TTL update skipped (may already be enabled)"

# Create DynamoDB table: idempotency
if table_exists "idempotency"; then
    echo "DynamoDB table 'idempotency' already exists, skipping creation"
else
    echo "Creating DynamoDB table: idempotency"
    awslocal dynamodb create-table \
      --table-name idempotency \
      --attribute-definitions AttributeName=pk,AttributeType=S \
      --key-schema AttributeName=pk,KeyType=HASH \
      --billing-mode PAY_PER_REQUEST \
      --tags Key=Environment,Value=local
    echo "Waiting for table 'idempotency' to become active..."
    awslocal dynamodb wait table-exists --table-name idempotency
fi

# Enable TTL on idempotency table
echo "Enabling TTL on idempotency table"
awslocal dynamodb update-time-to-live \
  --table-name idempotency \
  --time-to-live-specification Enabled=true,AttributeName=ttl || echo "Note: TTL update skipped (may already be enabled)"

# Create Kinesis stream
if stream_exists "rate-limit-events"; then
    echo "Kinesis stream 'rate-limit-events' already exists, skipping creation"
else
    echo "Creating Kinesis stream: rate-limit-events"
    awslocal kinesis create-stream \
      --stream-name rate-limit-events \
      --shard-count 1
fi

# Wait for stream to become active
echo "Waiting for Kinesis stream to become active..."
awslocal kinesis wait stream-exists --stream-name rate-limit-events

echo "============================================"
echo "LocalStack initialization complete!"
echo "============================================"

# List created resources
echo ""
echo "DynamoDB Tables:"
awslocal dynamodb list-tables

echo ""
echo "Kinesis Streams:"
awslocal kinesis list-streams