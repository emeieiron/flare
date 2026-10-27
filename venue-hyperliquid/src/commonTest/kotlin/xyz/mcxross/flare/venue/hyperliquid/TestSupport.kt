package xyz.mcxross.flare.venue.hyperliquid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.ExchangeAction
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.hyperliquid.signing.EvmSignature
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.MarketEntity
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.SelectedMarketEntity
import xyz.mcxross.flare.store.TransactionJournalDao

/** A client whose `/info` answers each request `type` from [responses]; anything else fails. */
internal fun testClient(responses: Map<String, String>): HyperliquidClient {
  val engine = MockEngine { request ->
    val body = (request.body as TextContent).text
    val type = Json.parseToJsonElement(body).jsonObject["type"]?.jsonPrimitive?.content
    val response = responses[type]
    if (response == null) respondError(HttpStatusCode.BadRequest, "Unexpected request")
    else respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
  }
  return HyperliquidClient(HttpClient(engine))
}

internal val MarketResponses =
  mapOf(
    "metaAndAssetCtxs" to Fixtures.META_AND_ASSET_CTXS,
    "spotMetaAndAssetCtxs" to Fixtures.SPOT_META_AND_ASSET_CTXS,
  )

internal fun memoryPreferences() = AppPreferences(MemoryPreferences())

private class MemoryPreferences : DataStore<Preferences> {
  override val data = MutableStateFlow(emptyPreferences())

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
    transform(data.value).also { data.value = it }
}

internal class MemoryMarketCache : MarketCacheDao {
  val markets = mutableMapOf<String, MarketEntity>()
  val candles = mutableListOf<CandleEntity>()

  override fun observeMarkets(): Flow<List<MarketEntity>> = flowOf(markets.values.toList())

  override suspend fun markets(): List<MarketEntity> = markets.values.sortedBy { it.marketAddress }

  override suspend fun upsertMarkets(markets: List<MarketEntity>) {
    markets.forEach { this.markets[it.marketAddress] = it }
  }

  override suspend fun clearMarkets() = markets.clear()

  override suspend fun candles(
    market: String,
    interval: String,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<CandleEntity> =
    candles.filter {
      it.marketAddress == market && it.interval == interval && it.openTimeMs in startTimeMs..endTimeMs
    }

  override suspend fun upsertCandles(candles: List<CandleEntity>) {
    this.candles.removeAll { old -> candles.any { it.marketAddress == old.marketAddress && it.interval == old.interval && it.openTimeMs == old.openTimeMs } }
    this.candles += candles
  }

  override suspend fun deleteCandlesBefore(cutoffMs: Long) {
    candles.removeAll { it.closeTimeMs < cutoffMs }
  }

  override suspend fun selectMarket(market: SelectedMarketEntity) {}

  override fun observeSelectedMarket(): Flow<SelectedMarketEntity?> = flowOf(null)

  override suspend fun clearCandles() = candles.clear()

  override suspend fun clearSelectedMarkets() {}
}

/** A client whose `/info` answers come from [answer], given the request's `type` and body. */
internal fun dynamicClient(answer: (type: String, body: JsonObject) -> String?): HyperliquidClient {
  val engine = MockEngine { request ->
    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
    val type = body["type"]?.jsonPrimitive?.content.orEmpty()
    val response = answer(type, body)
    if (response == null) respondError(HttpStatusCode.BadRequest, "Unexpected request")
    else respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
  }
  return HyperliquidClient(HttpClient(engine))
}

internal class MemoryJournal : TransactionJournalDao {
  val entries = MutableStateFlow<Map<String, PendingTransactionEntity>>(emptyMap())

  override suspend fun upsert(transaction: PendingTransactionEntity) =
    entries.update { it + (transaction.hash to transaction) }

  override fun observePending(): Flow<List<PendingTransactionEntity>> =
    entries.map { all -> all.values.sortedBy { it.createdAtMs } }

  override suspend fun pending(): List<PendingTransactionEntity> =
    entries.value.values.sortedBy { it.createdAtMs }

  override suspend fun find(hash: String): PendingTransactionEntity? = entries.value[hash]

  override suspend fun replaceHash(oldHash: String, newHash: String, state: String, updatedAtMs: Long): Int {
    val old = entries.value[oldHash] ?: return 0
    entries.update { it - oldHash + (newHash to old.copy(hash = newHash, state = state, updatedAtMs = updatedAtMs)) }
    return 1
  }

  override suspend fun remove(hash: String) = entries.update { it - hash }

  override suspend fun deleteOtherNetworks(network: String) =
    entries.update { all -> all.filterValues { it.network == network } }
}

/** Host tests can't load FastKrypto, so these signers only carry an address. */
internal class AddressOnlySigner(override val address: String) : EvmSigner {
  override suspend fun signTypedData(signingInput: ByteArray): EvmSignature =
    error("Host tests don't sign")
}

/** Keys as addresses: the owner's, and agent keys handed out in order from [agentKeys]. */
internal class FakeSigningWallet(
  private val preferences: AppPreferences,
  private val agentKeys: Iterator<String> = generateSequence(1) { it + 1 }
    .map { "0x" + it.toString(16).padStart(40, 'a') }
    .iterator(),
) : HyperliquidSigningWallet {
  val ownerPrompts = mutableListOf<VaultPrompt>()
  var confirmations = 0
  var discarded = 0
  var signingFails: Exception? = null
  /** Imported keys and the addresses they sign as. */
  val importable = mutableMapOf<String, String>()

  override val authorizationGeneration: Long = 0

  override fun requireAuthorization(generation: Long) {}

  override val profile: Flow<WalletProfile> =
    preferences.values.map { WalletProfile(it.ownerAddress, it.apiWalletAddress, it.ownerBackupConfirmed) }

  override suspend fun confirmIdentity(prompt: VaultPrompt) {
    confirmations += 1
  }

  override suspend fun <T> withOwnerSigner(prompt: VaultPrompt, block: suspend (EvmSigner) -> T): T {
    ownerPrompts += prompt
    signingFails?.let { throw it }
    return block(AddressOnlySigner(checkNotNull(preferences.values.first().ownerAddress).lowercase()))
  }

  override suspend fun <T> withAgentSigner(prompt: VaultPrompt, block: suspend (EvmSigner) -> T): T =
    block(AddressOnlySigner(checkNotNull(preferences.values.first().apiWalletAddress).lowercase()))

  override suspend fun createApiWallet(prompt: VaultPrompt): String =
    preferences.values.first().apiWalletAddress ?: newApiWallet(prompt)

  override suspend fun newApiWallet(prompt: VaultPrompt): String =
    agentKeys.next().also { preferences.setApiWallet(it) }

  override suspend fun discardApiWallet(prompt: VaultPrompt) {
    discarded += 1
    preferences.setApiWallet(null)
  }

  override suspend fun importApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (address: String) -> Unit,
  ): String {
    val address = importable.getValue(key)
    verify(address)
    preferences.registerProfile(AccountProfile("api_$address", apiWalletAddress = address))
    return address
  }

  override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup = error("Not used")

  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String = error("Not used")

  override suspend fun confirmOwnerBackup() {}

  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = error("Not used")

  override suspend fun exportApiWallet(prompt: VaultPrompt): String = error("Not used")

  override suspend fun removeOwner(prompt: VaultPrompt) {}

  override suspend fun removeApiWallet(prompt: VaultPrompt) {}

  override fun lock() {}
}

/** Records what would be signed and sent, and answers with [outcome]. */
internal class FakeSubmitter : HyperliquidSubmitter {
  val sent = mutableListOf<Pair<ExchangeAction, Long>>()
  /** The `vaultAddress` each L1 action was signed with. */
  val vaults = mutableListOf<String?>()
  var outcome: (ExchangeAction) -> ExchangeOutcome = { ExchangeOutcome.Accepted(ExchangeResponse.Default) }
  /** Runs before the request would be signed: what the journal holds by then. */
  var beforeSigning: suspend (nonce: Long) -> Unit = {}
  var afterSigning: suspend (nonce: Long) -> Unit = {}

  override suspend fun submit(
    action: UserSignedAction,
    signer: EvmSigner,
    nonce: Long,
    beforeSend: suspend () -> Unit,
  ): ExchangeOutcome {
    beforeSigning(nonce)
    beforeSend()
    afterSigning(nonce)
    sent += action to nonce
    return outcome(action)
  }

  override suspend fun submit(
    action: L1Action,
    signer: EvmSigner,
    nonce: Long,
    vaultAddress: String?,
    expiresAfter: Long?,
    beforeSend: suspend () -> Unit,
  ): ExchangeOutcome {
    beforeSigning(nonce)
    beforeSend()
    afterSigning(nonce)
    sent += action to nonce
    vaults += vaultAddress
    return outcome(action)
  }
}

/** Setup over fakes: the wallet, `/exchange` and the journal, with `/info` from [answer]. */
internal class SetupHarness(answer: (type: String, body: JsonObject) -> String? = { _, _ -> null }) {
  var now = 1_800_000_000_000L
  val preferences = memoryPreferences()
  val journal = MemoryJournal()
  val wallet = FakeSigningWallet(preferences)
  val submitter = FakeSubmitter()
  var answer: (type: String, body: JsonObject) -> String? = answer
  val client = dynamicClient { type, body -> this.answer(type, body) }
  val nonces = HyperliquidNonces(preferences) { now }
  val actions =
    HyperliquidActions(journal, preferences, wallet, client, submitter, nonces) { now }
  val setup = HyperliquidSetup(client, preferences, wallet, actions, nonces, { now }, verificationDelayMs = 0)

  suspend fun withOwner(owner: String = OWNER): SetupHarness = apply {
    preferences.registerProfile(AccountProfile("owner_$owner", ownerAddress = owner))
  }

  companion object {
    const val OWNER = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
  }
}

/** Setup for repositories whose tests never reach it. */
internal fun unusedSetup(client: HyperliquidClient, preferences: AppPreferences): HyperliquidSetup {
  val wallet = FakeSigningWallet(preferences)
  val nonces = HyperliquidNonces(preferences)
  val actions =
    HyperliquidActions(MemoryJournal(), preferences, wallet, client, FakeSubmitter(), nonces)
  return HyperliquidSetup(client, preferences, wallet, actions, nonces)
}

/** An account repository over [client] whose setup and fund moves the test never reaches. */
internal fun testAccounts(
  client: HyperliquidClient,
  preferences: AppPreferences,
  setup: HyperliquidSetup = unusedSetup(client, preferences),
  funds: HyperliquidFunds = unusedFunds(client, preferences),
): HyperliquidAccountRepository = HyperliquidAccountRepository(client, preferences, setup, funds)

internal fun unusedFunds(client: HyperliquidClient, preferences: AppPreferences): HyperliquidFunds {
  val actions =
    HyperliquidActions(
      MemoryJournal(), preferences, FakeSigningWallet(preferences), client, FakeSubmitter(),
      HyperliquidNonces(preferences),
    )
  return HyperliquidFunds(client, actions, preferences)
}
