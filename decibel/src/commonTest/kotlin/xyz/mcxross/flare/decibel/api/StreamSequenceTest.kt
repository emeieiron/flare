package xyz.mcxross.flare.decibel.api

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamSequenceTest {
  @Test
  fun classifiesFirstContiguousGapAndStaleMessages() {
    assertEquals(SequenceDisposition.FIRST, classifySequence(null, 7uL))
    assertEquals(SequenceDisposition.NEXT, classifySequence(7uL, 8uL))
    assertEquals(SequenceDisposition.GAP, classifySequence(7uL, 9uL))
    assertEquals(SequenceDisposition.STALE, classifySequence(7uL, 7uL))
    assertEquals(SequenceDisposition.STALE, classifySequence(7uL, 6uL))
  }

  @Test
  fun preservesUnsignedSequenceRange() {
    assertEquals(
      SequenceDisposition.NEXT,
      classifySequence(ULong.MAX_VALUE - 1uL, ULong.MAX_VALUE),
    )
  }
}
