package xyz.mcxross.flare.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.data.SwapOperation
import xyz.mcxross.flare.data.SwapOperationStatus

interface SwapOperationStore {
  val operations: Flow<List<SwapOperation>>
  suspend fun write(operation: SwapOperation)
}

/** Separate from the pending journal: final outcomes survive reconciliation and process restarts. */
class PreferencesSwapOperationStore(private val dataStore: DataStore<Preferences>) : SwapOperationStore {
  private val key = stringPreferencesKey("swap_operations_v1")
  private val json = Json { ignoreUnknownKeys = true }
  override val operations: Flow<List<SwapOperation>> = dataStore.data.map { decode(it[key]) }

  override suspend fun write(operation: SwapOperation) {
    dataStore.edit { preferences ->
      val saved = decode(preferences[key])
      val existing = saved.firstOrNull { it.id == operation.id }
      if (existing != null) {
        require(existing.profileId == operation.profileId && existing.quote == operation.quote &&
          (existing.preparedReference == operation.preparedReference ||
            existing.status == SwapOperationStatus.NOT_SUBMITTED && operation.status == SwapOperationStatus.PREPARED)) {
          "Swap operation identity changed"
        }
        require(existing.status !in FINAL || existing == operation) { "A finalized swap cannot be reopened" }
        require(operation.status != SwapOperationStatus.PREPARED || existing.status == SwapOperationStatus.NOT_SUBMITTED ||
          existing == operation) { "An unresolved swap cannot be prepared again" }
        require(operation.status != SwapOperationStatus.NOT_SUBMITTED || existing.status != SwapOperationStatus.PENDING) {
          "An uncertain submission cannot be retried"
        }
      }
      val updated = saved.filterNot { it.id == operation.id } + operation
      val pending = updated.filter { it.status !in FINAL && it.status != SwapOperationStatus.NOT_SUBMITTED }
      val history = updated.filter { it !in pending }.sortedByDescending { it.updatedAtMs }.take(100)
      preferences[key] = json.encodeToString((pending + history).sortedByDescending { it.updatedAtMs })
    }
  }

  private fun decode(value: String?): List<SwapOperation> =
    value?.let { json.decodeFromString<List<SwapOperation>>(it) } ?: emptyList()
}

val SwapOperation.isUnresolved: Boolean
  get() = status in setOf(SwapOperationStatus.PREPARED, SwapOperationStatus.SUBMITTING, SwapOperationStatus.PENDING)

private val FINAL = setOf(SwapOperationStatus.COMMITTED, SwapOperationStatus.ABORTED)
