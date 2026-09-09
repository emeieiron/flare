package xyz.mcxross.flare.feature.portfolio

import xyz.mcxross.flare.decibel.api.TransactionState

internal fun shortAddress(address: String): String =
  if (address.length <= 18) address else address.take(10) + "…" + address.takeLast(6)

internal fun TransactionState.label(): String =
  when (this) {
    TransactionState.Simulating -> "Simulating transaction"
    TransactionState.AwaitingAuthorization -> "Awaiting authorization"
    TransactionState.Submitting -> "Submitting transaction"
    is TransactionState.Pending -> "Pending · ${shortAddress(hash)}"
    is TransactionState.Committed -> "Committed · ${shortAddress(hash)}"
    is TransactionState.Failed -> "Failed · $message"
  }
