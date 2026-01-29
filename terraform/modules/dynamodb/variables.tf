variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "rate_limit_table_config" {
  type = object({
    billing_mode   = string
    read_capacity  = optional(number)
    write_capacity = optional(number)
  })
}

variable "idempotency_table_config" {
  type = object({
    billing_mode   = string
    read_capacity  = optional(number)
    write_capacity = optional(number)
  })
}
