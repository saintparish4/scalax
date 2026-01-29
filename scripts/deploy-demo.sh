#!/bin/bash
# deploy-demo.sh - Spin up full stack in ~5 minutes

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Deploying rate limiter demo environment..."

# Check for required environment variable
if [ -z "$ECR_IMAGE" ]; then
  echo "Error: ECR_IMAGE environment variable is required"
  echo "   Example: export ECR_IMAGE=123456789.dkr.ecr.us-east-1.amazonaws.com/rate-limiter:latest"
  exit 1
fi

# Check for AWS credentials
if ! aws sts get-caller-identity > /dev/null 2>&1; then
  echo "Error: AWS credentials not configured"
  echo "   Run: aws configure"
  exit 1
fi

# Deploy infrastructure
cd "$PROJECT_ROOT/terraform"

echo "Initializing Terraform..."
terraform init

echo "Applying Terraform configuration..."
# shellcheck disable=SC1009,SC1073,SC1072,SC2086
terraform apply -auto-approve \
  -var-file=environments/demo.tfvars \
  -var="ecs_desired_count=1" \
  -var="container_image=${ECR_IMAGE}" \
  -var="enable_autoscaling=false" \
  -var="enable_kinesis_firehose=false"

# Wait for healthy
ALB_DNS=$(terraform output -raw load_balancer_dns)
echo "Waiting for service to be healthy..."

MAX_WAIT=600  # 10 minutes
INTERVAL=10   # Check every 10 seconds
ELAPSED=0

while [ $ELAPSED -lt $MAX_WAIT ]; do
  # Check if ALB is responding
  if curl -sf --max-time 5 "http://$ALB_DNS/health" > /dev/null 2>&1; then
    echo "Service is healthy!"
    break
  fi

  # Check if ALB is responding at all (even if not healthy)
  if curl -sf --max-time 5 "http://$ALB_DNS" > /dev/null 2>&1; then
    echo "Service is responding but not yet healthy, waiting..."
  else
    echo "Service not yet available, waiting... (${ELAPSED}s/${MAX_WAIT}s)"
  fi

  sleep $INTERVAL
  ELAPSED=$((ELAPSED + INTERVAL))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
  echo "Timeout: Service did not become healthy within ${MAX_WAIT} seconds"
  echo "   Check ECS service status and CloudWatch logs"
  exit 1
fi

echo ""
echo "Demo ready at: http://$ALB_DNS"
echo "Estimated cost: ~\$0.50/hour"
echo "Remember to run: ./scripts/teardown-demo.sh when done"
echo ""
echo "Quick test:"
echo "   curl http://$ALB_DNS/health"
