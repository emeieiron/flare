# Flare architecture patterns

Templates for the patterns in `SKILL.md`, trimmed to the parts that matter. Names follow Flare's code; rename them for your app. Each section ends with where Flare implements it. Links point at `main`.

## Contents

1. Version catalog and module dependencies
2. MVVM feature (Kotlin + Compose route)
3. SwiftUI bridge
4. Live data: snapshot + stream
5. Transaction pipeline and journal
6. Vault contract and foreground authorization
7. Storage at v1
8. Where Flare implements everything

## 1. Version catalog and module dependencies

```toml
# gradle/libs.versions.toml (look up current stable versions; see SKILL.md §4)
[versions]
kaptos = "<stable>"
ksui = "<stable>"
bcs = "<stable>"

[libraries]
kaptos = { module = "xyz.mcxross.kaptos:kaptos", version.ref = "kaptos" }
ksui = { module = "xyz.mcxross.ksui:ksui", version.ref = "ksui" }
bcs = { module = "xyz.mcxross.bcs:bcs", version.ref = "bcs" }
```

```kotlin
// protocol/build.gradle.kts: headless, so no Compose plugins and no platform UI.
kotlin {
  jvm()                                   // fast protocol tests on the JVM
  androidLibrary { /* … */ }
  iosArm64(); iosSimulatorArm64()
  sourceSets.commonMain.dependencies {
    api(libs.kaptos)                      // or ksui; chain types are part of this module's API
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
  }
}
// shared depends on protocol; androidApp and the iOS framework depend on shared. Nothing points up.
```

Flare: [settings.gradle.kts](https://github.com/emeieiron/flare/blob/main/settings.gradle.kts), [decibel/build.gradle.kts](https://github.com/emeieiron/flare/blob/main/decibel/build.gradle.kts), [gradle/libs.versions.toml](https://github.com/emeieiron/flare/blob/main/gradle/libs.versions.toml)

## 2. MVVM feature

```kotlin
// shared/src/commonMain/.../feature/markets/
data class MarketsUiState(
  val loading: Boolean = true,
  val quotes: List<MarketQuote> = emptyList(),
  val stale: Boolean = false,          // keep showing data; never blank the screen on a refresh
  val error: String? = null,
)

sealed interface MarketsIntent {
  data class Search(val query: String) : MarketsIntent
  data class ToggleFavorite(val market: String) : MarketsIntent
}

sealed interface MarketsEffect { data class OpenMarket(val market: String) : MarketsEffect }

class MarketsViewModel(private val markets: MarketsRepository) : ViewModel() {
  private val local = MutableStateFlow(MarketsUiState())
  val uiState: StateFlow<MarketsUiState> = local.asStateFlow()

  private val effectChannel = Channel<MarketsEffect>(Channel.BUFFERED)
  val effects: Flow<MarketsEffect> = effectChannel.receiveAsFlow()

  fun onIntent(intent: MarketsIntent) {
    when (intent) {
      is MarketsIntent.Search -> local.update { it.copy(/* … */) }
      is MarketsIntent.ToggleFavorite -> viewModelScope.launch { markets.toggleFavorite(intent.market) }
    }
  }

  /** Live prices while the screen is visible; the caller's lifecycle cancels it. */
  suspend fun followLive() = markets.connectLive()
}
```

```kotlin
// androidApp: the route binds lifecycle; the screen only renders.
@Composable
fun MarketsRoute(onOpen: (String) -> Unit, viewModel: MarketsViewModel = koinViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val owner = LocalLifecycleOwner.current
  LaunchedEffect(owner, viewModel) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.followLive() }
  }
  LaunchedEffect(viewModel) {
    viewModel.effects.collect { if (it is MarketsEffect.OpenMarket) onOpen(it.market) }
  }
  MarketsScreen(state, viewModel::onIntent)
}

@Composable
fun MarketsScreen(state: MarketsUiState, onIntent: (MarketsIntent) -> Unit) { /* pure */ }
```

Register each ViewModel and repository in one DI module, for example Koin's `viewModelOf(::MarketsViewModel)` and `singleOf(::DefaultMarketsRepository) { bind<MarketsRepository>() }`.

Flare: [MarketsViewModel.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/markets/MarketsViewModel.kt), [MarketsScreen.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/markets/MarketsScreen.kt) (route and screen), [TradeScreen.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/trade/TradeScreen.kt) (`repeatOnLifecycle` → `followLiveChart`), [OnboardingViewModel.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/onboarding/OnboardingViewModel.kt) (effects), [FlareModule.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/di/FlareModule.kt)

## 3. SwiftUI bridge

Expose one small, typed facade per feature from `iosMain`. Swift never sees the DI graph, chain-SDK types or Room.

```kotlin
// shared/src/iosMain/.../bridge/Observation.kt
class Observation internal constructor(private val job: Job) {
  fun cancel() = job.cancel()                                   // idempotent
}

internal fun <T> StateFlow<T>.observe(onEach: (T) -> Unit): Observation =
  Observation(MainScope().launch { collect { onEach(it) } })   // current value first, then updates, on the main thread

// shared/src/iosMain/.../bridge/MarketsFeature.kt
class MarketsFeature internal constructor(private val viewModel: MarketsViewModel) {
  val state: MarketsUiState get() = viewModel.uiState.value      // initial snapshot, no gap
  fun observe(onEach: (MarketsUiState) -> Unit): Observation = viewModel.uiState.observe(onEach)
  fun followLive(): Observation = Observation(MainScope().launch { viewModel.followLive() })
  fun search(query: String) = viewModel.onIntent(MarketsIntent.Search(query))
  fun toggleFavorite(market: String) = viewModel.onIntent(MarketsIntent.ToggleFavorite(market))
}
```

```swift
// iosApp/Features/Markets/MarketsModel.swift
@MainActor
final class MarketsModel: ObservableObject {
    @Published private(set) var state: MarketsUiState
    private let feature: MarketsFeature
    private var observations: [Observation] = []

    init(feature: MarketsFeature) {
        self.feature = feature
        self.state = feature.state
    }

    func start() {
        guard observations.isEmpty else { return }
        observations = [
            feature.observe { [weak self] in self?.state = $0 },
            feature.followLive(),
        ]
    }

    func stop() {
        observations.forEach { $0.cancel() }
        observations = []
    }

    func search(_ query: String) { feature.search(query: query) }
}

struct MarketsView: View {
    @StateObject var model: MarketsModel

    var body: some View {
        List(model.state.quotes, id: \.market.address) { MarketRow(quote: $0) }
            .onAppear { model.start() }
            .onDisappear { model.stop() }
    }
}
```

- **Lifetimes.** Stopping observation isn't the same as destroying the feature. Keep tab features alive, and scope detail features to their route. Close account-sensitive features when the profile changes.
- **ViewModel ownership.** AndroidX ViewModels on iOS need an explicit `ViewModelStore` owner that you clear when the feature's lifetime ends. See [Android's KMP ViewModel guide](https://developer.android.com/kotlin/multiplatform/viewmodel).
- **What Swift sees.** Sealed classes arrive as class hierarchies, so offer typed methods (as above) or map them to Swift enums in the adapter. Kotlin exceptions don't cross into Swift unless declared, so catch expected failures in the facade and return app-owned error types.
- **Cancellation.** Test cancellation explicitly: cancelling a Swift `Task` doesn't stop Kotlin work by itself.

## 4. Live data: snapshot + stream

```kotlin
suspend fun followCandles(market: String, interval: Interval) {
  stream.subscribe(setOf(CandleTopic(market, interval))).collect { event ->
    when (event) {
      is StreamEvent.Connected, is StreamEvent.SequenceGap ->            // updates may have been missed
        candles.update { it.caughtUpWith(rest.latestPage(market, interval), interval.ms) }
      is StreamEvent.Message ->
        (event.data as? Candle)?.let { candle -> candles.update { it.withLiveCandle(candle) } }
      is StreamEvent.Disconnected, is StreamEvent.Rejected ->
        markStale()                                                       // keep showing data
    }
  }
}

/** Pure and unit-tested: replaces the candle it updates, or follows the latest one. */
fun List<Candle>.withLiveCandle(candle: Candle): List<Candle> = /* … */
```

- The stream reconnects with backoff, and each collection owns one socket.
- Collect only while visible: the route or `.onAppear` controls it.
- Page older history quietly as the user scrolls back. Use stable keys (for example, time slots) so prepending doesn't move what's on screen.

Flare: [DecibelStreamService.kt](https://github.com/emeieiron/flare/blob/main/decibel/src/commonMain/kotlin/xyz/mcxross/flare/decibel/api/DecibelStreamService.kt), [CandleSeries.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/CandleSeries.kt), [MarketsRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/MarketsRepository.kt) (`ChartRepository`), [TradeViewModel.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/feature/trade/TradeViewModel.kt) (`followLiveChart`, `catchUpChart`, `loadOlderCandles`)

## 5. Transaction pipeline and journal

```kotlin
sealed interface TransactionState {
  data object Simulating : TransactionState
  data object AwaitingAuthorization : TransactionState
  data object Submitting : TransactionState
  data class Pending(val hash: String) : TransactionState            // sent; outcome not known yet
  data class Committed(val hash: String) : TransactionState
  data class Failed(
    val message: String,                                               // one plain sentence
    val hash: String? = null,
    val committed: Boolean = false,                                    // landed on chain but aborted
    val definitelyNotSubmitted: Boolean = false,                       // safe to retry as new
  ) : TransactionState
}

fun execute(command: Command, signer: Signer): Flow<TransactionState> = flow {
  emit(TransactionState.Simulating)
  val tx = build(command)                          // exact decimal strings → chain units
  val simulation = simulate(tx)
  if (!simulation.success) {
    emit(TransactionState.Failed(plain(simulation), definitelyNotSubmitted = true))
    return@flow
  }
  emit(TransactionState.AwaitingAuthorization)
  val signed = signer.sign(tx)                     // vault: biometric / foreground gate
  journal.record(signed.hash, command)             // before the network ever sees it
  emit(TransactionState.Submitting)
  submit(signed)                                   // a timeout here is NOT a failure
  emit(TransactionState.Pending(signed.hash))
  emit(awaitOutcome(signed.hash))                  // Committed or Failed(committed = true)
}

// On launch and on returning to the foreground (in the app coordinator, not a screen):
suspend fun reconcile() = journal.pending().forEach { entry -> journal.resolve(entry, lookUp(entry.hash)) }
```

- **Sponsorship.** Pass a fee payer (your proxy) by default. When it can't cover the fee, show the estimate and ask before signing as the payer. Require a balance that covers the gas the network reserves, not just the estimate.

Flare: [DecibelTradingService.kt](https://github.com/emeieiron/flare/blob/main/decibel/src/commonMain/kotlin/xyz/mcxross/flare/decibel/api/DecibelTradingService.kt) (`TransactionState`, `execute`), [TradingRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/TradingRepository.kt) (journal, reconciliation), [GasSponsorshipRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/GasSponsorshipRepository.kt)

## 6. Vault contract and foreground authorization

```kotlin
data class VaultPrompt(
  val title: String,                        // names the action: "Withdraw USDC"
  val subtitle: String,                     // "Confirm your identity"
  val requireFreshAuthorization: Boolean = false,   // owner actions: always prompt
)

interface WalletVault {
  suspend fun store(slot: SecretSlot, secret: ByteArray, prompt: VaultPrompt)
  suspend fun read(slot: SecretSlot, prompt: VaultPrompt): ByteArray   // caller wipes it after use
  suspend fun remove(slot: SecretSlot, prompt: VaultPrompt)
  fun lock()                                // on background: drop authority, cancel signing
}
```

- **Platform vaults.** Implement it once per platform in `androidMain` and `iosMain`, and construct it in the app shell. Wrap it in a foreground decorator that holds decrypted material only for the current visit and bumps a generation counter on `lock()`. Any signing that started in an older generation is refused.
- **Previews and tests.** Use a stub vault that always throws, so nothing in a harness can sign.
- **Cancelled prompts.** A cancelled prompt raises a distinct `Cancelled` error. Treat it as "nothing happened", not as a failure to report.

Flare: [WalletVault.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/security/WalletVault.kt), [ForegroundWalletVault.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/security/ForegroundWalletVault.kt), [AndroidWalletVault.kt](https://github.com/emeieiron/flare/blob/main/shared/src/androidMain/kotlin/xyz/mcxross/flare/security/AndroidWalletVault.kt), [IosWalletVault.kt](https://github.com/emeieiron/flare/blob/main/shared/src/iosMain/kotlin/xyz/mcxross/flare/security/IosWalletVault.kt), [AndroidManifest.xml](https://github.com/emeieiron/flare/blob/main/androidApp/src/main/AndroidManifest.xml) (backups off)

## 7. Storage at v1

```kotlin
@Database(entities = [MarketEntity::class, PendingTransactionEntity::class], version = 1, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() { /* DAOs */ }

fun buildDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
  builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Default)
    .build()                                // no migrations or destructive fallbacks before release

// build.gradle.kts (Room 3 plugin; Room 2 uses `room { }`)
room3 { schemaDirectory("$projectDir/schemas") }  // commit schemas/…/1.json
```

Flare: [FlareDatabase.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/store/FlareDatabase.kt), [schemas](https://github.com/emeieiron/flare/tree/main/shared/schemas), [AppPreferences.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/store/AppPreferences.kt)

## 8. Where Flare implements everything

| Pattern | Files |
|---|---|
| App shells | [MainActivity.kt](https://github.com/emeieiron/flare/blob/main/androidApp/src/main/kotlin/xyz/mcxross/flare/MainActivity.kt), [MainViewController.kt](https://github.com/emeieiron/flare/blob/main/shared/src/iosMain/kotlin/xyz/mcxross/flare/MainViewController.kt), [ContentView.swift](https://github.com/emeieiron/flare/blob/main/iosApp/iosApp/ContentView.swift) |
| Launch stages, unlock, restore | [App.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/App.kt) (`LaunchStage`) |
| Runtime config and proxy URLs | [FlareRuntimeConfig.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/core/FlareRuntimeConfig.kt), [decibel-worker](https://github.com/emeieiron/decibel-worker) |
| Protocol module | [DecibelClient.kt](https://github.com/emeieiron/flare/blob/main/decibel/src/commonMain/kotlin/xyz/mcxross/flare/decibel/DecibelClient.kt) and `decibel/…/api/` |
| Sessions and key import with verification | [SessionRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/SessionRepository.kt), [WalletRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/WalletRepository.kt), [AccountRepository.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/AccountRepository.kt) (`importTradingKey`) |
| Credential detection | [WalletCredential.kt](https://github.com/emeieiron/flare/blob/main/shared/src/commonMain/kotlin/xyz/mcxross/flare/data/WalletCredential.kt) |
| Screen capture protection | [ScreenCapture.android.kt](https://github.com/emeieiron/flare/blob/main/shared/src/androidMain/kotlin/xyz/mcxross/flare/design/ScreenCapture.android.kt), [ScreenCapture.ios.kt](https://github.com/emeieiron/flare/blob/main/shared/src/iosMain/kotlin/xyz/mcxross/flare/design/ScreenCapture.ios.kt) (still a no-op) |
| Tests | [decibel/src/commonTest](https://github.com/emeieiron/flare/tree/main/decibel/src/commonTest), [shared/src/commonTest](https://github.com/emeieiron/flare/tree/main/shared/src/commonTest) |
