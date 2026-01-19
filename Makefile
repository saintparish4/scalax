.PHONY: help start stop logs run test integration status clean \
       docker-build docker-run docker-run-detached docker-stop docker-logs \
       curl-check curl-status curl-health curl-ready all-tests dev docker-restart \
       tf-validate

# Default target
.DEFAULT_GOAL := help

help: ## Show this help message
	@echo "Usage: make <command>"
	@echo ""
	@echo "Local Development (sbt + LocalStack):"
	@echo "  make start        Start LocalStack only (for sbt development)"
	@echo "  make stop         Stop LocalStack"
	@echo "  make logs         Follow LocalStack logs"
	@echo "  make run          Run the application with sbt (requires LocalStack)"
	@echo "  make dev          Start LocalStack + run app with sbt"
	@echo "  make test         Run unit tests"
	@echo "  make integration  Run integration tests"
	@echo "  make status       Check LocalStack and AWS resource status"
	@echo "  make clean        Stop all containers and clean build artifacts"
	@echo ""
	@echo "Docker (full containerized stack):"
	@echo "  make docker-build         Build the application Docker image"
	@echo "  make docker-run           Start full stack (LocalStack + App)"
	@echo "  make docker-run-detached  Start full stack in background"
	@echo "  make docker-stop          Stop Docker services"
	@echo "  make docker-logs          Follow Docker logs"
	@echo "  make docker-restart       Restart Docker stack"
	@echo ""
	@echo "API Testing:"
	@echo "  make curl-check   Test POST /v1/ratelimit/check"
	@echo "  make curl-status  Test GET /v1/ratelimit/status/:key"
	@echo "  make curl-health  Test GET /health"
	@echo "  make curl-ready   Test GET /ready"
	@echo ""
	@echo "Terraform:"
	@echo "  make tf-validate  Validate Terraform configuration"

# =============================================================================
# Local Development (sbt + LocalStack)
# =============================================================================

start: ## Start LocalStack only (for sbt development)
	@echo "Starting LocalStack..."
	docker-compose up -d localstack
	@echo "Waiting for LocalStack to be ready..."
	@timeout=120; \
	while [ $$timeout -gt 0 ]; do \
		if curl -s http://localhost:4566/_localstack/health | grep -q '"dynamodb": "running"'; then \
			echo "LocalStack is ready!"; \
			echo ""; \
			echo "To run the application:"; \
			echo "  make run"; \
			exit 0; \
		fi; \
		echo "  Waiting for DynamoDB..."; \
		sleep 2; \
		timeout=$$((timeout-2)); \
	done; \
	echo "LocalStack failed to start within timeout period"; \
	exit 1

stop: ## Stop LocalStack
	@echo "Stopping LocalStack..."
	docker-compose down

logs: ## Follow LocalStack logs
	docker-compose logs -f localstack

run: ## Run the application with sbt (requires LocalStack)
	@echo "Starting Rate Limiter Platform..."
	@echo ""
	@command -v sbt >/dev/null 2>&1 || { \
		echo "Error: sbt not found in PATH."; \
		echo "Install sbt: https://www.scala-sbt.org/download.html"; \
		echo "Or use Docker: make docker-run"; \
		exit 1; \
	}
	USE_LOCALSTACK=true \
	DYNAMODB_ENDPOINT=http://localhost:4566 \
	KINESIS_ENDPOINT=http://localhost:4566 \
	AWS_ACCESS_KEY_ID=test \
	AWS_SECRET_ACCESS_KEY=test \
	AWS_REGION=us-east-1 \
	sbt run

dev: start run ## Start LocalStack and run the application with sbt

test: ## Run unit tests
	@echo "Running unit tests..."
	@command -v sbt >/dev/null 2>&1 || { \
		echo "Error: sbt not found. Install sbt or use Docker."; \
		exit 1; \
	}
	sbt test

integration: ## Run integration tests (uses TestContainers, no LocalStack needed)
	@echo "Running integration tests..."
	@command -v sbt >/dev/null 2>&1 || { \
		echo "Error: sbt not found. Install sbt or use Docker."; \
		exit 1; \
	}
	sbt "testOnly *IntegrationSpec"

all-tests: test integration ## Run all tests (unit + integration)
	@echo "All tests completed!"

status: ## Check LocalStack and AWS resource status
	@echo "Checking LocalStack status..."
	@curl -s http://localhost:4566/_localstack/health 2>/dev/null | python3 -m json.tool 2>/dev/null | \
		perl -pe 's/": "running"/": "\e[0;32mrunning\e[0m"/g; \
		          s/": "available"/": "\e[0;32mavailable\e[0m"/g; \
		          s/": "disabled"/": "\e[0;31mdisabled\e[0m"/g' || echo "  (LocalStack not running)"
	@echo ""
	@if command -v aws >/dev/null 2>&1; then \
		echo "DynamoDB Tables:"; \
		aws --endpoint-url=http://localhost:4566 dynamodb list-tables 2>/dev/null || echo "  (Unable to query - check LocalStack is running)"; \
		echo ""; \
		echo "Kinesis Streams:"; \
		aws --endpoint-url=http://localhost:4566 kinesis list-streams 2>/dev/null || echo "  (Unable to query - check LocalStack is running)"; \
	else \
		echo "Note: AWS CLI not installed (optional - not required for the application)"; \
		echo "  To list tables/streams, install AWS CLI or check via Docker:"; \
		echo "    docker-compose exec localstack awslocal dynamodb list-tables"; \
		echo "    docker-compose exec localstack awslocal kinesis list-streams"; \
	fi

clean: ## Stop all containers and clean build artifacts
	@echo "Cleaning up..."
	-docker-compose --profile app down -v 2>/dev/null
	-docker-compose down -v 2>/dev/null
	rm -rf target project/target .bsp .metals

# =============================================================================
# Docker (full containerized stack)
# =============================================================================

docker-build: ## Build the application Docker image
	@echo "Building Docker image..."
	docker build -t rate-limiter-platform:latest .
	@echo ""
	@echo "Image built: rate-limiter-platform:latest"
	@echo "Run with: make docker-run"

docker-run: ## Start full stack (LocalStack + App) with Docker
	@echo "Starting full stack with Docker..."
	@echo "This will build the app image and start LocalStack + Application"
	@echo ""
	docker-compose --profile app up --build

docker-run-detached: ## Start full stack in background
	@echo "Starting full stack with Docker (detached)..."
	docker-compose --profile app up --build -d
	@echo ""
	@echo "Services started. Useful commands:"
	@echo "  make docker-logs   - Follow logs"
	@echo "  make curl-health   - Check health"
	@echo "  make docker-stop   - Stop services"

docker-stop: ## Stop Docker services
	@echo "Stopping Docker services..."
	-docker-compose --profile app down

docker-logs: ## Follow Docker logs
	docker-compose --profile app logs -f

docker-restart: docker-stop docker-run ## Restart Docker stack

# =============================================================================
# API Testing
# =============================================================================

curl-check: ## Test POST /v1/ratelimit/check
	@echo "Testing rate limit check endpoint..."
	@curl -s -X POST http://localhost:8080/v1/ratelimit/check \
		-H "Content-Type: application/json" \
		-H "Authorization: Bearer test-api-key" \
		-d '{"key":"user:123","cost":1}' | python3 -m json.tool 2>/dev/null || \
		(echo "Error: Service not available at http://localhost:8080" && \
		 echo "Start with: make dev  OR  make docker-run")

curl-status: ## Test GET /v1/ratelimit/status/:key
	@echo "Testing rate limit status endpoint..."
	@curl -s http://localhost:8080/v1/ratelimit/status/user:123 | python3 -m json.tool 2>/dev/null || \
		echo "Error: Service not available"

curl-health: ## Test GET /health
	@echo "Testing health endpoint..."
	@curl -s http://localhost:8080/health | python3 -m json.tool 2>/dev/null || \
		echo "Error: Service not available"

curl-ready: ## Test GET /ready
	@echo "Testing readiness endpoint..."
	@curl -s http://localhost:8080/ready | python3 -m json.tool 2>/dev/null || \
		echo "Error: Service not available"

# =============================================================================
# Terraform
# =============================================================================

tf-validate: ## Validate Terraform configuration
	@echo "Validating Terraform configuration..."
	@command -v terraform >/dev/null 2>&1 || { \
		echo "Error: terraform not found in PATH."; \
		echo "Install Terraform: https://www.terraform.io/downloads"; \
		exit 1; \
	}
	@echo "Initializing Terraform providers..."
	cd terraform && terraform init -upgrade
	@echo "Validating Terraform configuration..."
	cd terraform && terraform validate
