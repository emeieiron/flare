package xyz.mcxross.flare.core

import kotlinx.coroutines.CancellationException

suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Throwable) {
    Result.failure(error)
  }
