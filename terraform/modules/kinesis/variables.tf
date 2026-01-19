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