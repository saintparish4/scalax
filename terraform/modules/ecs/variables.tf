variable "project_name" {
  description = "Name of the project"
  type        = string
}

variable "environment" {
  description = "Environment name"
  type        = string
}

variable "vpc_id" {
  description = "ID of the VPC"
  type        = string
}

variable "public_subnet_ids" {
  description = "IDs of public subnets (for ALB)"
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "IDs of private subnets (for ECS tasks)"
  type        = list(string)
}

variable "container_image" {
  description = "Docker image for the application"
  type        = string
}

variable "container_port" {
  description = "Port the container listens on"
  type        = number
  default     = 8080
}

variable "desired_count" {
  description = "Desired number of ECS tasks"
  type        = number
  default     = 2
}

variable "cpu" {
  description = "CPU units for ECS task"
  type        = number
  default     = 256
}

variable "memory" {
  description = "Memory (MB) for ECS task"
  type        = number
  default     = 512
}

variable "rate_limit_table" {
  description = "Name of the rate limits DynamoDB table"
  type        = string
}

variable "rate_limit_table_arn" {
  description = "ARN of the rate limits DynamoDB table"
  type        = string
}

variable "idempotency_table" {
  description = "Name of the idempotency DynamoDB table"
  type        = string
}

variable "idempotency_table_arn" {
  description = "ARN of the idempotency DynamoDB table"
  type        = string
}

variable "kinesis_stream" {
  description = "Name of the Kinesis stream"
  type        = string
}

variable "kinesis_stream_arn" {
  description = "ARN of the Kinesis stream"
  type        = string
}
