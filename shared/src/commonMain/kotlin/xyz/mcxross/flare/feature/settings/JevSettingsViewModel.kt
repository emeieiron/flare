package xyz.mcxross.flare.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.security.JevCredentialMetadata
import xyz.mcxross.flare.security.JevCredentials

fun interface JevKeyChecker {
  suspend fun check(key: String): Boolean
}

class DirectJevKeyChecker(
  private val client: HttpClient,
) : JevKeyChecker {
  override suspend fun check(key: String): Boolean = try {
    client.get("https://api.typesafe.ai/v1/models") {
      header("Authorization", "Bearer $key")
    }.status.value in 200..299
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    false
  }
}

data class JevSettingsUiState(
  val metadata: JevCredentialMetadata = JevCredentialMetadata(),
  val keyInput: String = "",
  val sheetOpen: Boolean = false,
  val busy: Boolean = false,
  val notice: String? = null,
) {
  override fun toString(): String =
    "JevSettingsUiState(metadata=$metadata, keyInput=<redacted>, " +
      "sheetOpen=$sheetOpen, busy=$busy, notice=$notice)"
}

sealed interface JevSettingsIntent {
  data object Open : JevSettingsIntent
  data object Dismiss : JevSettingsIntent
  data class ChangeKey(val value: String) : JevSettingsIntent {
    override fun toString(): String = "ChangeKey(<redacted>)"
  }
  data object Save : JevSettingsIntent
  data object Check : JevSettingsIntent
  data object Remove : JevSettingsIntent
  data class SetEnabled(val enabled: Boolean) : JevSettingsIntent
}

class JevSettingsViewModel(
  private val credentials: JevCredentials,
  private val checker: JevKeyChecker,
) : ViewModel() {
  private val mutable = MutableStateFlow(JevSettingsUiState())
  val uiState = mutable.asStateFlow()
  private var operation: Job? = null

  init {
    viewModelScope.launch {
      credentials.metadata.collect { value -> mutable.update { it.copy(metadata = value) } }
    }
  }

  fun onIntent(intent: JevSettingsIntent) {
    when (intent) {
      JevSettingsIntent.Open -> mutable.update { it.copy(sheetOpen = true, notice = null, keyInput = "") }
      JevSettingsIntent.Dismiss -> {
        operation?.cancel()
        mutable.update { it.copy(sheetOpen = false, keyInput = "", notice = null, busy = false) }
      }
      is JevSettingsIntent.ChangeKey -> if (!mutable.value.busy) {
        mutable.update { it.copy(keyInput = intent.value.take(4_097), notice = null) }
      }
      JevSettingsIntent.Save -> withInput { key ->
        credentials.store(key)
        mutable.update { it.copy(notice = "API key saved. Enable Jev confluence to use it.") }
      }
      JevSettingsIntent.Check -> withInput { key ->
        val accepted = checker.check(key)
        mutable.update {
          it.copy(
            notice = if (accepted) "API key accepted."
              else "Jev could not accept this key. Check the key and try again."
          )
        }
      }
      JevSettingsIntent.Remove -> work {
        credentials.remove()
        mutable.update { it.copy(keyInput = "", notice = "API key removed.") }
      }
      is JevSettingsIntent.SetEnabled -> work { credentials.setEnabled(intent.enabled) }
    }
  }

  private fun withInput(block: suspend (String) -> Unit) {
    val key = mutable.value.keyInput
    if (!JevCredentials.validInput(key)) {
      mutable.update {
        it.copy(notice = "Enter an API key without spaces or control characters (maximum 4,096 characters).")
      }
      return
    }
    mutable.update { it.copy(keyInput = "") }
    work { block(key) }
  }

  private fun work(block: suspend () -> Unit) {
    if (mutable.value.busy) return
    operation = viewModelScope.launch {
      mutable.update { it.copy(busy = true, notice = null) }
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutable.update { it.copy(notice = "Could not complete this action. Open Flare and try again.") }
      } finally {
        mutable.update { it.copy(busy = false) }
      }
    }
  }

  override fun onCleared() {
    mutable.update { it.copy(keyInput = "") }
  }
}
