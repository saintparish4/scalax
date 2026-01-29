variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "shard_count" {
  type    = number
  default = 1
}

variable "retention_hours" {
  type    = number
  default = 24
}

variable "enable_firehose" {
  type    = bool
  default = false
}

variable "s3_bucket_name" {
  type    = string
  default = ""
}