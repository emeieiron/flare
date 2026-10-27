package xyz.mcxross.flare.aptos.swap

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.kaptos.core.crypto.sha3Hash
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.UserTransactionResponse

/** Checks net asset movements in addition to the router's declared limits. Gas is separate. */
fun ValidatedSwapPlan.validateEffects(result: UserTransactionResponse) {
  require(result.success && sameAddress(result.sender, request.owner)) { "Unexpected swap simulation" }
  val inputStore = primaryStore(request.owner, AptosSwapAssets.APT)
  val outputStore = primaryStore(request.owner, AptosSwapAssets.USDC)
  var spent = 0uL
  var refunded = 0uL
  var received = 0uL
  var outputWithdrawn = 0uL
  for (event in result.events) {
    val parts = event.type?.split("::") ?: continue
    if (parts.size != 3 || !sameAddress(parts[0], "0x1") || parts[1] != "fungible_asset" ||
      parts[2] !in setOf("Withdraw", "Deposit")) continue
    val data = checkNotNull(event.data).jsonObject
    val store = data.getValue("store").jsonPrimitive.content
    val amount = data.getValue("amount").jsonPrimitive.content.toULong()
    if (sameAddress(store, inputStore)) {
      if (parts[2] == "Withdraw") spent = add(spent, amount) else refunded = add(refunded, amount)
    }
    if (sameAddress(store, outputStore)) {
      if (parts[2] == "Deposit") received = add(received, amount) else outputWithdrawn = add(outputWithdrawn, amount)
    }
  }
  require(spent >= refunded && spent - refunded in 1uL..maximumInput) { "Unexpected APT debit" }
  require(received >= outputWithdrawn && received - outputWithdrawn >= minimumOutput) {
    "The swap does not deliver the approved USDC amount"
  }
}

internal fun primaryStore(owner: String, metadata: String): String =
  AccountAddress(sha3Hash(AccountAddress.fromString(owner).data +
    AccountAddress.fromString(metadata).data + byteArrayOf(0xfc.toByte()))).toString()

private fun add(left: ULong, right: ULong): ULong {
  require(right <= ULong.MAX_VALUE - left) { "Asset movement overflow" }
  return left + right
}
