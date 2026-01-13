package core

// Result of an idempotency check
sealed trait IdempotencyResult

object IdempotencyResult:
  // This is a new request - proceed with processing
  case class New(idempotencyKey: String) extends IdempotencyResult

  // This is a duplicate request - return cached response
  case class Duplicate(
      idempotencyKey: String,
      cachedResponse: Option[CachedResponse],
  ) extends IdempotencyResult

// Cached response for idempotency operations
case class CachedResponse(
    statusCode: Int,
    body: String,
    headers: Map[String, String],
)

/** Trait for idempotency storage backends
  *
  * Implementations must provide first-writer-wins semantics using conditional
  * writes to prevent duplicate processing
  */
trait IdempotencyStore[F[_]]:
  /** Check if an idempotency key exists If not, atomically create it
    * (first-writer-wins)
    *
    * @param key
    *   unique idempotency key
    * @param ttlSeconds
    *   how long to retain the key
    * @return
    *   New if this is the first request, Duplicate otherwise
    */
  def checkOrCreate(key: String, ttlSeconds: Long): F[IdempotencyResult]

  /** Store the response for a completed idempotent operation Called after
    * successful processing of a New request
    */
  def storeResponse(key: String, response: CachedResponse): F[Unit]

  // Health check for the storage backend
  def healthCheck: F[Boolean]
