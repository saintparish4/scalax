variable "project_name" {
  description = "Name of the project"
  type        = string
}

variable "environment" {
  description = "Environment name"
  type        = string
}

variable "shard_count" {
  description = "Number of shards"
  type        = number
  default     = 1
}

variable "retention_hours" {
  description = "Data retention period in hours"
  type        = number
  default     = 24
}

# Firehose settings
variable "firehose_buffer_size" {
  description = "Buffer size in MB before Firehose delivers to S3 (1-128)"
  type        = number
  default     = 5
}

variable "firehose_buffer_interval" {
  description = "Buffer interval in seconds before Firehose delivers to S3 (60-900)"
  type        = number
  default     = 300
}