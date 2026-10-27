package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState

/** Contract identity, independent of display symbols and a venue's trading balances. */
@Serializable
data class SwapAsset(val network: String, val address: String, val decimals: Int) {
  init {
    require(network.isNotBlank() && address.isNotBlank())
    require(decimals in 0..18)
  }
}

@Serializable
enum class SwapAmountKind { EXACT_INPUT, EXACT_OUTPUT }

/** All quantities are unsigned integer base units, never formatted decimal amounts. */
@Serializable
data class SwapRequest(
  val owner: String,
  val input: SwapAsset,
  val output: SwapAsset,
  val kind: SwapAmountKind,
  val amount: String,
  val slippageBps: Int = 50,
) {
  init {
    require(owner.isNotBlank())
    require(input.network == output.network && input.address != output.address)
    require(amount.matches(Regex("[1-9][0-9]*")) && amount.toULongOrNull() != null)
    require(slippageBps in 1..100)
  }
}

@Serializable
data class SwapFee(val asset: SwapAsset, val amount: String)

@Serializable
data class SwapQuote(
  val id: String,
  val provider: String,
  val request: SwapRequest,
  val expectedInput: String,
  val expectedOutput: String,
  val maximumInput: String,
  val minimumOutput: String,
  val fees: List<SwapFee>,
  val expiresAtMs: Long,
)

@Serializable
enum class SwapOperationStatus { PREPARED, SUBMITTING, PENDING, COMMITTED, ABORTED, NOT_SUBMITTED }

@Serializable
data class SwapOperation(
  val id: String,
  val profileId: String,
  val quote: SwapQuote,
  val status: SwapOperationStatus,
  val reference: String,
  val updatedAtMs: Long,
  val message: String? = null,
)

/** A swap ends at the owner wallet. Funding and trading are separate consumers of this contract. */
interface SwapRepository {
  val operations: Flow<List<SwapOperation>>

  suspend fun quote(request: SwapRequest): SwapQuote

  /** Executes only a retained, unchanged quote, with fresh owner authorization. */
  fun execute(
    quoteId: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  /** Reconciles existing references; never creates replacement transactions. */
  suspend fun reconcile(): List<SwapOperation>
}
