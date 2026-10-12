package xyz.mcxross.flare.hyperliquid.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import xyz.mcxross.flare.hyperliquid.model.CancelStatus
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.hyperliquid.model.TwapOrderStatus

/** `/exchange` reply shapes, from the exchange endpoint docs. */
class ExchangeReplyTest {
  @Test
  fun parsesEveryOrderStatus() {
    val outcome =
      parse(
        """{"status":"ok","response":{"type":"order","data":{"statuses":[
          {"resting":{"oid":77738308}},
          {"resting":{"oid":77738309,"cloid":"0x0123456789ABCDEF0123456789abcdef"}},
          {"filled":{"totalSz":"0.02","avgPx":"1891.4","oid":77747314}},
          {"filled":{"totalSz":"0.02","avgPx":"1891.4","oid":77747315,
            "cloid":"0x00000000000000000000000000000001"}},
          {"error":"Order must have minimum value of ${'$'}10."},
          "waitingForFill",
          "waitingForTrigger",
          "somethingNew",
          {"partial":{}}
        ]}}}"""
      )

    assertEquals(
      ExchangeOutcome.Accepted(
        ExchangeResponse.Orders(
          listOf(
            OrderStatus.Resting(77738308L),
            OrderStatus.Resting(77738309L, Cloid.parse("0x0123456789abcdef0123456789abcdef")),
            OrderStatus.Filled("0.02", "1891.4", 77747314L),
            OrderStatus.Filled(
              "0.02",
              "1891.4",
              77747315L,
              Cloid.parse("0x00000000000000000000000000000001"),
            ),
            OrderStatus.Error("Order must have minimum value of $10."),
            OrderStatus.WaitingForFill,
            OrderStatus.WaitingForTrigger,
            OrderStatus.Unrecognized("somethingNew"),
            OrderStatus.Unrecognized("partial"),
          )
        )
      ),
      outcome,
    )
  }

  @Test
  fun parsesDefault() {
    assertEquals(
      ExchangeOutcome.Accepted(ExchangeResponse.Default),
      parse("""{"status":"ok","response":{"type":"default"}}"""),
    )
  }

  @Test
  fun parsesCancelStatuses() {
    assertEquals(
      ExchangeOutcome.Accepted(
        ExchangeResponse.Cancels(
          listOf(
            CancelStatus.Success,
            CancelStatus.Error("Order was never placed, already canceled, or filled."),
          )
        )
      ),
      parse(
        """{"status":"ok","response":{"type":"cancel","data":{"statuses":["success",
          {"error":"Order was never placed, already canceled, or filled."}]}}}"""
      ),
    )
  }

  @Test
  fun parsesTwapReplies() {
    assertEquals(
      ExchangeOutcome.Accepted(ExchangeResponse.TwapPlaced(TwapOrderStatus.Running(77738308L))),
      parse(
        """{"status":"ok","response":{"type":"twapOrder",
          "data":{"status":{"running":{"twapId":77738308}}}}}"""
      ),
    )
    assertEquals(
      ExchangeOutcome.Accepted(
        ExchangeResponse.TwapPlaced(TwapOrderStatus.Error("Invalid TWAP duration: 1 min(s)"))
      ),
      parse(
        """{"status":"ok","response":{"type":"twapOrder",
          "data":{"status":{"error":"Invalid TWAP duration: 1 min(s)"}}}}"""
      ),
    )
    assertEquals(
      ExchangeOutcome.Accepted(ExchangeResponse.TwapCanceled(CancelStatus.Success)),
      parse("""{"status":"ok","response":{"type":"twapCancel","data":{"status":"success"}}}"""),
    )
    assertEquals(
      ExchangeOutcome.Accepted(
        ExchangeResponse.TwapCanceled(
          CancelStatus.Error("TWAP was never placed, already canceled, or filled.")
        )
      ),
      parse(
        """{"status":"ok","response":{"type":"twapCancel",
          "data":{"status":{"error":"TWAP was never placed, already canceled, or filled."}}}}"""
      ),
    )
  }

  @Test
  fun keepsUnmodelledResponseTypes() {
    assertEquals(
      ExchangeOutcome.Accepted(
        ExchangeResponse.Other(
          "createSubAccount",
          JsonPrimitive("0x1d9470d4b963f552e6f671a81619d395877bf409"),
        )
      ),
      parse(
        """{"status":"ok","response":{"type":"createSubAccount",
          "data":"0x1d9470d4b963f552e6f671a81619d395877bf409"}}"""
      ),
    )
  }

  @Test
  fun treatsStatusErrAsADefiniteRejection() {
    assertEquals(
      ExchangeOutcome.Rejected("User or API Wallet 0x0123 does not exist."),
      parse("""{"status":"err","response":"User or API Wallet 0x0123 does not exist."}"""),
    )
    assertEquals(
      ExchangeOutcome.Rejected("Hyperliquid rejected the action"),
      parse("""{"status":"err"}"""),
    )
    val long = parse("""{"status":"err","response":"${"x".repeat(1_000)}"}""")
    assertIs<ExchangeOutcome.Rejected>(long)
    assertEquals(300, long.reason.length)
  }

  @Test
  fun treatsUnexpectedShapesAsUnknown() {
    listOf(
        """[]""",
        """"ok"""",
        """{"response":{"type":"default"}}""",
        """{"status":"maybe","response":{"type":"default"}}""",
        """{"status":"ok"}""",
        """{"status":"ok","response":"done"}""",
        """{"status":"ok","response":{"type":"order","data":{}}}""",
        """{"status":"ok","response":{"type":"twapOrder"}}""",
      )
      .forEach { body -> assertIs<ExchangeOutcome.Unknown>(parse(body), body) }
  }

  @Test
  fun classifiesHttpFailures() {
    assertTrue(isDefiniteRejection(400))
    assertTrue(isDefiniteRejection(422))
    assertTrue(isDefiniteRejection(429))
    assertFalse(isDefiniteRejection(408))
    assertFalse(isDefiniteRejection(0))
    assertFalse(isDefiniteRejection(200))
    assertFalse(isDefiniteRejection(500))
    assertFalse(isDefiniteRejection(502))
  }

  private fun parse(text: String): ExchangeOutcome =
    parseExchangeReply(Json.parseToJsonElement(text))
}
