package xyz.mcxross.flare.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.testing.MemoryPreferences

class SwapOperationStoreTest {
  @Test
  fun restartRetainsTheFinalOutcomeAndOriginalSponsorshipReference() = runTest {
    val data = MemoryPreferences()
    val store = PreferencesSwapOperationStore(data)
    val prepared = operation("swap")
    store.write(prepared)
    store.write(prepared.copy(status = SwapOperationStatus.COMMITTED, reference = "0xconfirmed"))
    val restored = PreferencesSwapOperationStore(data).operations.first().single()
    assertEquals(SwapOperationStatus.COMMITTED, restored.status)
    assertEquals("sponsor:swap", restored.preparedReference)
    assertEquals("0xconfirmed", restored.reference)
    assertFailsWith<IllegalArgumentException> { store.write(prepared) }
  }

  @Test
  fun uncertainOperationsCannotBeRepreparedOrMarkedUnsubmitted() = runTest {
    val store = PreferencesSwapOperationStore(MemoryPreferences())
    val prepared = operation("swap")
    val pending = prepared.copy(status = SwapOperationStatus.PENDING)
    store.write(pending)
    assertFailsWith<IllegalArgumentException> { store.write(prepared) }
    assertFailsWith<IllegalArgumentException> { store.write(pending.copy(status = SwapOperationStatus.NOT_SUBMITTED)) }
    assertFailsWith<IllegalArgumentException> { store.write(pending.copy(profileId = "another")) }
  }

  @Test
  fun aDefiniteRejectionAllowsANewSignedReferenceForTheSameQuote() = runTest {
    val store = PreferencesSwapOperationStore(MemoryPreferences())
    val prepared = operation("swap")
    store.write(prepared.copy(status = SwapOperationStatus.NOT_SUBMITTED))
    store.write(prepared.copy(reference = "0xselfpaid", preparedReference = "0xselfpaid"))
    assertEquals("0xselfpaid", store.operations.first().single().reference)
  }

  @Test
  fun historyRetentionNeverDiscardsUnresolvedOperations() = runTest {
    val store = PreferencesSwapOperationStore(MemoryPreferences())
    store.write(operation("pending"))
    for (index in 1..105) store.write(operation("done-$index").copy(
      status = SwapOperationStatus.COMMITTED, updatedAtMs = index.toLong()))
    val saved = store.operations.first()
    assertEquals(101, saved.size)
    assertTrue(saved.any { it.id == "pending" && it.isUnresolved })
  }
}

private fun operation(id: String): SwapOperation {
  val request = SwapRequest("0x1", SwapAsset("aptos:1", "0xa", 8),
    SwapAsset("aptos:1", "0xb", 6), SwapAmountKind.EXACT_OUTPUT, "1000000")
  val quote = SwapQuote(id, "provider", request, "100", "1000000", "101", "1000000", emptyList(), 30000)
  return SwapOperation(id, "profile", quote, SwapOperationStatus.PREPARED, "sponsor:$id", 1)
}
