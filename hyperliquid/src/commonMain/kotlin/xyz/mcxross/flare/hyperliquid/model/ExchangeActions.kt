package xyz.mcxross.flare.hyperliquid.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.signing.Eip712Struct
import xyz.mcxross.flare.hyperliquid.signing.Eip712Value

/**
 * An action sent to `/exchange`. Field names and their order are those of the official Python SDK
 * (`hyperliquid/exchange.py`, `hyperliquid/utils/signing.py`): for an [L1Action] the order is
 * part of what is signed (its msgpack bytes are hashed), so it must never change.
 */
sealed interface ExchangeAction {
  /** The action's `type`. */
  val type: String
}

/**
 * An action signed over a phantom agent (`sign_l1_action`): orders, cancels, leverage, TWAP,
 * vault transfers. An approved agent (API wallet) may sign these for its user, and they may act
 * for a sub-account or vault through `vaultAddress`.
 */
sealed interface L1Action : ExchangeAction {
  /** The action as sent, which is also what its msgpack hash commits to. */
  fun toWire(): JsonObject

  /** False for actions the SDK always signs without a `vaultAddress`. */
  val allowsVaultAddress: Boolean
    get() = true
}

/**
 * An action signed by the account owner as human-readable EIP-712 (`sign_user_signed_action`)
 * under the `HyperliquidSignTransaction` domain: agent and builder-fee approvals, withdrawals and
 * transfers. Agents cannot sign these. The nonce is assigned when signing; it is also the
 * message's `nonce` or `time` field.
 */
sealed interface UserSignedAction : ExchangeAction {
  /** `HyperliquidTransaction:<Name>`. */
  val primaryType: String

  /** The signed struct, fields in the SDK's type order. */
  fun message(deployment: HyperliquidDeployment, nonce: Long): Eip712Struct

  /** The action as sent: the message's fields plus `type` and `signatureChainId`. */
  fun toWire(deployment: HyperliquidDeployment, nonce: Long): JsonObject
}

enum class TimeInForce(val wire: String) {
  /** Add liquidity only (post only). */
  ALO("Alo"),
  /** Immediate or cancel. */
  IOC("Ioc"),
  /** Good til canceled. */
  GTC("Gtc"),
}

enum class TpSl(val wire: String) {
  TAKE_PROFIT("tp"),
  STOP_LOSS("sl"),
}

sealed interface OrderType {
  data class Limit(val timeInForce: TimeInForce) : OrderType

  /** A trigger order; [isMarket] fills at market once triggered, otherwise at the limit price. */
  data class Trigger(val triggerPrice: HlDecimal, val isMarket: Boolean, val tpsl: TpSl) : OrderType
}

/** How the orders of one `order` action relate. */
enum class OrderGrouping(val wire: String) {
  NA("na"),
  /** TP/SL children attached to the parent order(s) in the same action. */
  NORMAL_TPSL("normalTpsl"),
  /** TP/SL for the whole position, resized as it changes. */
  POSITION_TPSL("positionTpsl"),
}

/**
 * A builder fee on an order: [address] receives [tenthsOfBasisPoint] of the notional (10 = 1 bp),
 * up to what the user approved with [ApproveBuilderFee].
 */
class BuilderFee(address: String, val tenthsOfBasisPoint: Int) {
  val address: String = normalizeEvmAddress(address, "builder")

  init {
    require(tenthsOfBasisPoint >= 0) { "A builder fee must not be negative" }
  }

  override fun equals(other: Any?): Boolean =
    other is BuilderFee &&
      other.address == address &&
      other.tenthsOfBasisPoint == tenthsOfBasisPoint

  override fun hashCode(): Int = 31 * address.hashCode() + tenthsOfBasisPoint

  override fun toString(): String = "BuilderFee($address, $tenthsOfBasisPoint)"
}

/**
 * One order: wire keys `a` (asset), `b` (is buy), `p` (price), `s` (size), `r` (reduce only),
 * `t` (type) and optional `c` (cloid). Prices and sizes go out via [HlDecimal.toWire]; round them
 * with [HyperliquidAsset.roundPrice] and [HyperliquidAsset.roundSize] first.
 */
data class OrderRequest(
  val asset: Int,
  val isBuy: Boolean,
  val price: HlDecimal,
  val size: HlDecimal,
  val reduceOnly: Boolean,
  val type: OrderType,
  val cloid: Cloid? = null,
) {
  init {
    require(asset >= 0) { "Asset id must not be negative" }
    require(price.signum > 0) { "Price must be positive" }
    require(size.signum > 0) { "Size must be positive" }
  }

  internal fun toWire(): JsonObject = buildJsonObject {
    put("a", asset)
    put("b", isBuy)
    put("p", price.toWire())
    put("s", size.toWire())
    put("r", reduceOnly)
    putJsonObject("t") {
      when (type) {
        is OrderType.Limit -> putJsonObject("limit") { put("tif", type.timeInForce.wire) }
        is OrderType.Trigger ->
          putJsonObject("trigger") {
            put("isMarket", type.isMarket)
            put("triggerPx", type.triggerPrice.toWire())
            put("tpsl", type.tpsl.wire)
          }
      }
    }
    cloid?.let { put("c", it.hex) }
  }
}

/** `order`: one or more orders, optionally grouped as TP/SL and carrying a builder fee. */
data class PlaceOrders(
  val orders: List<OrderRequest>,
  val grouping: OrderGrouping = OrderGrouping.NA,
  val builder: BuilderFee? = null,
) : L1Action {
  init {
    require(orders.isNotEmpty()) { "At least one order is required" }
  }

  override val type: String
    get() = "order"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put("orders", buildJsonArray { orders.forEach { add(it.toWire()) } })
    put("grouping", grouping.wire)
    builder?.let {
      putJsonObject("builder") {
        put("b", it.address)
        put("f", it.tenthsOfBasisPoint)
      }
    }
  }
}

data class CancelRequest(val asset: Int, val oid: Long)

/** `cancel` by order id. The optional `f` (fast) flag is never sent. */
data class CancelOrders(val cancels: List<CancelRequest>) : L1Action {
  init {
    require(cancels.isNotEmpty()) { "At least one cancel is required" }
  }

  override val type: String
    get() = "cancel"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put(
      "cancels",
      buildJsonArray {
        cancels.forEach { cancel ->
          add(
            buildJsonObject {
              put("a", cancel.asset)
              put("o", cancel.oid)
            }
          )
        }
      },
    )
  }
}

data class CancelByCloidRequest(val asset: Int, val cloid: Cloid)

/** `cancelByCloid`: cancels by client order id, so an order whose placement is unknown can go. */
data class CancelOrdersByCloid(val cancels: List<CancelByCloidRequest>) : L1Action {
  init {
    require(cancels.isNotEmpty()) { "At least one cancel is required" }
  }

  override val type: String
    get() = "cancelByCloid"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put(
      "cancels",
      buildJsonArray {
        cancels.forEach { cancel ->
          add(
            buildJsonObject {
              put("asset", cancel.asset)
              put("cloid", cancel.cloid.hex)
            }
          )
        }
      },
    )
  }
}

/**
 * `scheduleCancel` (dead man's switch): cancels every open order at [timeMs], which must be at
 * least 5 s ahead; `null` clears a scheduled cancel. At most 10 triggers a day.
 */
data class ScheduleCancel(val timeMs: Long? = null) : L1Action {
  override val type: String
    get() = "scheduleCancel"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    timeMs?.let { put("time", it) }
  }
}

/** `updateLeverage`: sets cross ([isCross]) or isolated leverage on a perp. */
data class UpdateLeverage(val asset: Int, val isCross: Boolean, val leverage: Int) : L1Action {
  init {
    require(leverage > 0) { "Leverage must be positive" }
  }

  override val type: String
    get() = "updateLeverage"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put("asset", asset)
    put("isCross", isCross)
    put("leverage", leverage)
  }
}

/**
 * `updateIsolatedMargin`: adds (positive) or removes (negative) margin of an isolated position, in
 * micro-USDC (`ntli`). `isBuy` is always true until Hyperliquid has hedge mode, as in the SDK.
 */
data class UpdateIsolatedMargin(val asset: Int, val microUsdc: Long) : L1Action {
  override val type: String
    get() = "updateIsolatedMargin"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put("asset", asset)
    put("isBuy", true)
    put("ntli", microUsdc)
  }

  companion object {
    fun ofUsdc(asset: Int, amount: HlDecimal): UpdateIsolatedMargin =
      UpdateIsolatedMargin(asset, usdcToMicros(amount))
  }
}

/**
 * `twapOrder`: wire `{"type":"twapOrder","twap":{"a","b","s","r","m","t"}}` with `m` the duration
 * in minutes and `t` whether slices are randomized (exchange endpoint docs; the SDK has no TWAP).
 */
data class PlaceTwap(
  val asset: Int,
  val isBuy: Boolean,
  val size: HlDecimal,
  val reduceOnly: Boolean,
  val minutes: Int,
  val randomize: Boolean,
) : L1Action {
  init {
    require(size.signum > 0) { "Size must be positive" }
    require(minutes > 0) { "Duration must be positive" }
  }

  override val type: String
    get() = "twapOrder"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    putJsonObject("twap") {
      put("a", asset)
      put("b", isBuy)
      put("s", size.toWire())
      put("r", reduceOnly)
      put("m", minutes)
      put("t", randomize)
    }
  }
}

/** `twapCancel`: wire `{"type":"twapCancel","a":asset,"t":twapId}`. */
data class CancelTwap(val asset: Int, val twapId: Long) : L1Action {
  override val type: String
    get() = "twapCancel"

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put("a", asset)
    put("t", twapId)
  }
}

/**
 * `vaultTransfer`: deposits into or withdraws from a vault (HLP: 4-day lock-up after the latest
 * deposit; user vaults: 1 day), in micro-USDC.
 *
 * **Signer:** it is an L1 action — the SDK's `vault_usd_transfer` signs it with `sign_l1_action`,
 * the phantom-agent scheme API wallets use, always with `vaultAddress = None` — so structurally
 * an approved agent can sign it. Neither the SDK nor the docs restrict it to the owner (unlike
 * `approveBuilderFee`, whose docs say "must be signed by the user's main wallet"); the SDK's
 * `basic_agent.py` only says agents cannot "transfer or withdraw funds", which are the user-signed
 * `usdSend`/`withdraw3`. Not yet confirmed against the live chain; PR 3's testnet test should.
 */
class VaultTransfer(vaultAddress: String, val isDeposit: Boolean, val microUsdc: Long) : L1Action {
  val vaultAddress: String = normalizeEvmAddress(vaultAddress, "vaultAddress")

  init {
    require(microUsdc > 0L) { "Amount must be positive" }
  }

  override val type: String
    get() = "vaultTransfer"

  override val allowsVaultAddress: Boolean
    get() = false

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put("vaultAddress", vaultAddress)
    put("isDeposit", isDeposit)
    put("usd", microUsdc)
  }

  override fun equals(other: Any?): Boolean =
    other is VaultTransfer &&
      other.vaultAddress == vaultAddress &&
      other.isDeposit == isDeposit &&
      other.microUsdc == microUsdc

  override fun hashCode(): Int =
    (vaultAddress.hashCode() * 31 + isDeposit.hashCode()) * 31 + microUsdc.hashCode()

  override fun toString(): String = "VaultTransfer($vaultAddress, isDeposit=$isDeposit, $microUsdc)"

  companion object {
    fun ofUsdc(vaultAddress: String, isDeposit: Boolean, amount: HlDecimal): VaultTransfer =
      VaultTransfer(vaultAddress, isDeposit, usdcToMicros(amount))
  }
}

/**
 * `createSubAccount`: an L1 action the SDK signs without `vaultAddress`. Per the docs
 * (trading/sub-accounts), up to 10 sub-accounts can be created after reaching $100,000 in volume,
 * one more per further $100M of volume, at most 50; they share the master's fee tier but not its
 * referral discount, and each adds 2 API-wallet slots. Sub-accounts have no keys: the master (or
 * its agent) trades for one by signing with `vaultAddress` set to it.
 */
data class CreateSubAccount(val name: String) : L1Action {
  init {
    require(name.isNotBlank()) { "A sub-account needs a name" }
  }

  override val type: String
    get() = "createSubAccount"

  override val allowsVaultAddress: Boolean
    get() = false

  override fun toWire(): JsonObject = buildJsonObject {
    put("type", type)
    put("name", name)
  }
}

/**
 * `approveAgent`: lets [agentAddress] (an API wallet) sign L1 actions for the owner and its
 * sub-accounts. Agents cannot sign user-signed actions (withdrawals, transfers, approvals).
 *
 * **Replacing and revoking** (docs, "Nonces and API wallets › API wallet pruning"): approving a
 * new unnamed agent deregisters the existing unnamed one, and approving with an existing
 * [agentName] deregisters the agent registered under that name. Neither the SDK nor the docs have
 * a separate revoke action, so an agent is revoked by approving a replacement under the same name
 * (e.g. a fresh, discarded key). Agents also lapse when a `valid_until <ms>` suffix on the name
 * expires (at most 180 days ahead) or when the account no longer has funds. An account holds one
 * unnamed and up to 3 named agents, plus 2 per sub-account. Never reuse a deregistered agent's
 * key: its nonce set may be pruned, making old signed actions replayable.
 *
 * A `null` [agentName] signs `""` and leaves `agentName` off the wire, as the SDK does.
 */
class ApproveAgent(agentAddress: String, val agentName: String? = null) : UserSignedAction {
  val agentAddress: String = normalizeEvmAddress(agentAddress, "agentAddress")

  override val type: String
    get() = "approveAgent"

  override val primaryType: String
    get() = "HyperliquidTransaction:ApproveAgent"

  override fun message(deployment: HyperliquidDeployment, nonce: Long): Eip712Struct =
    Eip712Struct(
      primaryType,
      listOf(
        "hyperliquidChain" to Eip712Value.Str(deployment.hyperliquidChain),
        "agentAddress" to Eip712Value.Address(agentAddress),
        "agentName" to Eip712Value.Str(agentName ?: ""),
        "nonce" to Eip712Value.Uint64(nonce),
      ),
    )

  override fun toWire(deployment: HyperliquidDeployment, nonce: Long): JsonObject =
    userWire(deployment) {
      put("agentAddress", agentAddress)
      agentName?.let { put("agentName", it) }
      put("nonce", nonce)
    }

  override fun equals(other: Any?): Boolean =
    other is ApproveAgent && other.agentAddress == agentAddress && other.agentName == agentName

  override fun hashCode(): Int = 31 * agentAddress.hashCode() + agentName.hashCode()

  override fun toString(): String = "ApproveAgent($agentAddress, $agentName)"
}

/**
 * `approveBuilderFee`: the most [builder] may charge on this account's orders, as a percent string
 * (`"0.001%"` = 1 tenth of a basis point). Must be signed by the owner, not an agent (docs,
 * "Builder codes"); one approval covers perps and spot. At most 10 active builder approvals.
 *
 * **Revoking:** the docs say a user "can revoke permissions at any time" but name no separate
 * action, and the SDK has none; the approval is a maximum, so approving `"0%"`
 * ([revoke]) takes the permission back. `maxBuilderFee` reads the current value to confirm it.
 */
class ApproveBuilderFee(builder: String, val maxFeeRate: String) : UserSignedAction {
  val builder: String = normalizeEvmAddress(builder, "builder")

  init {
    require(maxFeeRate.endsWith("%")) { "maxFeeRate is a percent string such as 0.001%" }
    requireNotNull(maxFeeRate.dropLast(1).toHlDecimalOrNull()?.takeIf { it.signum >= 0 }) {
      "maxFeeRate is a percent string such as 0.001%"
    }
  }

  override val type: String
    get() = "approveBuilderFee"

  override val primaryType: String
    get() = "HyperliquidTransaction:ApproveBuilderFee"

  override fun message(deployment: HyperliquidDeployment, nonce: Long): Eip712Struct =
    Eip712Struct(
      primaryType,
      listOf(
        "hyperliquidChain" to Eip712Value.Str(deployment.hyperliquidChain),
        "maxFeeRate" to Eip712Value.Str(maxFeeRate),
        "builder" to Eip712Value.Address(builder),
        "nonce" to Eip712Value.Uint64(nonce),
      ),
    )

  override fun toWire(deployment: HyperliquidDeployment, nonce: Long): JsonObject =
    userWire(deployment) {
      put("maxFeeRate", maxFeeRate)
      put("builder", builder)
      put("nonce", nonce)
    }

  override fun equals(other: Any?): Boolean =
    other is ApproveBuilderFee && other.builder == builder && other.maxFeeRate == maxFeeRate

  override fun hashCode(): Int = 31 * builder.hashCode() + maxFeeRate.hashCode()

  override fun toString(): String = "ApproveBuilderFee($builder, $maxFeeRate)"

  companion object {
    /** Approves up to [tenthsOfBasisPoint] (10 = 1 bp = `"0.01%"`). */
    fun ofTenthsOfBasisPoint(builder: String, tenthsOfBasisPoint: Int): ApproveBuilderFee {
      require(tenthsOfBasisPoint >= 0) { "A builder fee must not be negative" }
      val percent = HlDecimal.of(tenthsOfBasisPoint.toLong(), 3)
      return ApproveBuilderFee(builder, "$percent%")
    }

    /** Takes the builder's permission back by approving a zero maximum. */
    fun revoke(builder: String): ApproveBuilderFee = ApproveBuilderFee(builder, "0%")
  }
}

/** `withdraw3`: withdraws USDC to [destination] on Arbitrum through the bridge ($1 fee). */
class Withdraw(destination: String, val amount: HlDecimal) : UserSignedAction {
  val destination: String = normalizeEvmAddress(destination, "destination")

  init {
    require(amount.signum > 0) { "Amount must be positive" }
  }

  override val type: String
    get() = "withdraw3"

  override val primaryType: String
    get() = "HyperliquidTransaction:Withdraw"

  override fun message(deployment: HyperliquidDeployment, nonce: Long): Eip712Struct =
    destinationAmountTime(primaryType, deployment, destination, amount, nonce)

  override fun toWire(deployment: HyperliquidDeployment, nonce: Long): JsonObject =
    userWire(deployment) {
      put("destination", destination)
      put("amount", amount.toWire())
      put("time", nonce)
    }

  override fun equals(other: Any?): Boolean =
    other is Withdraw && other.destination == destination && other.amount == amount

  override fun hashCode(): Int = 31 * destination.hashCode() + amount.hashCode()

  override fun toString(): String = "Withdraw($destination, $amount)"
}

/** `usdSend`: sends perp USDC to another Hyperliquid account, off the EVM bridge. */
class UsdSend(destination: String, val amount: HlDecimal) : UserSignedAction {
  val destination: String = normalizeEvmAddress(destination, "destination")

  init {
    require(amount.signum > 0) { "Amount must be positive" }
  }

  override val type: String
    get() = "usdSend"

  override val primaryType: String
    get() = "HyperliquidTransaction:UsdSend"

  override fun message(deployment: HyperliquidDeployment, nonce: Long): Eip712Struct =
    destinationAmountTime(primaryType, deployment, destination, amount, nonce)

  override fun toWire(deployment: HyperliquidDeployment, nonce: Long): JsonObject =
    userWire(deployment) {
      put("destination", destination)
      put("amount", amount.toWire())
      put("time", nonce)
    }

  override fun equals(other: Any?): Boolean =
    other is UsdSend && other.destination == destination && other.amount == amount

  override fun hashCode(): Int = 31 * destination.hashCode() + amount.hashCode()

  override fun toString(): String = "UsdSend($destination, $amount)"
}

/**
 * `usdClassTransfer`: moves USDC between the spot and perp balances ([toPerp] = spot → perp). For
 * a sub-account the SDK appends ` subaccount:<address>` to the amount and sends no
 * `vaultAddress`.
 */
class UsdClassTransfer(val amount: HlDecimal, val toPerp: Boolean, subAccount: String? = null) :
  UserSignedAction {
  val subAccount: String? = subAccount?.let { normalizeEvmAddress(it, "subAccount") }

  init {
    require(amount.signum > 0) { "Amount must be positive" }
  }

  /** The signed `amount` field. */
  val wireAmount: String
    get() = amount.toWire() + (subAccount?.let { " subaccount:$it" } ?: "")

  override val type: String
    get() = "usdClassTransfer"

  override val primaryType: String
    get() = "HyperliquidTransaction:UsdClassTransfer"

  override fun message(deployment: HyperliquidDeployment, nonce: Long): Eip712Struct =
    Eip712Struct(
      primaryType,
      listOf(
        "hyperliquidChain" to Eip712Value.Str(deployment.hyperliquidChain),
        "amount" to Eip712Value.Str(wireAmount),
        "toPerp" to Eip712Value.Bool(toPerp),
        "nonce" to Eip712Value.Uint64(nonce),
      ),
    )

  override fun toWire(deployment: HyperliquidDeployment, nonce: Long): JsonObject =
    userWire(deployment) {
      put("amount", wireAmount)
      put("toPerp", toPerp)
      put("nonce", nonce)
    }

  override fun equals(other: Any?): Boolean =
    other is UsdClassTransfer &&
      other.amount == amount &&
      other.toPerp == toPerp &&
      other.subAccount == subAccount

  override fun hashCode(): Int =
    (amount.hashCode() * 31 + toPerp.hashCode()) * 31 + subAccount.hashCode()

  override fun toString(): String = "UsdClassTransfer($amount, toPerp=$toPerp, $subAccount)"
}

/** USDC as an integer of micro-USDC; more than 6 decimal places cannot be sent. */
fun usdcToMicros(amount: HlDecimal): Long {
  require(amount.decimalPlaces <= USDC_DECIMALS) {
    "$amount has more than $USDC_DECIMALS decimal places"
  }
  var micros = amount.unscaled
  repeat(USDC_DECIMALS - amount.decimalPlaces) {
    require(micros in (Long.MIN_VALUE / 10)..(Long.MAX_VALUE / 10)) { "$amount is too large" }
    micros *= 10
  }
  return micros
}

private const val USDC_DECIMALS = 6

private fun UserSignedAction.userWire(
  deployment: HyperliquidDeployment,
  fields: JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
  put("type", type)
  put("signatureChainId", deployment.signatureChainId)
  put("hyperliquidChain", deployment.hyperliquidChain)
  fields()
}

private fun destinationAmountTime(
  primaryType: String,
  deployment: HyperliquidDeployment,
  destination: String,
  amount: HlDecimal,
  time: Long,
): Eip712Struct =
  Eip712Struct(
    primaryType,
    listOf(
      "hyperliquidChain" to Eip712Value.Str(deployment.hyperliquidChain),
      "destination" to Eip712Value.Str(destination),
      "amount" to Eip712Value.Str(amount.toWire()),
      "time" to Eip712Value.Uint64(time),
    ),
  )
