package xyz.mcxross.flare.strategies

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface DecisionQuestion {
  val instructions: String

  data class Choice(override val instructions: String, val criteria: Map<String, String>) :
    DecisionQuestion

  data class Noul(override val instructions: String) : DecisionQuestion

  data class Score(override val instructions: String, val criteria: List<String>) :
    DecisionQuestion
}

data class DecisionQuery(
  val state: JsonObject,
  val questions: Map<String, DecisionQuestion>,
  val model: String = "jev-1.13.0",
)

sealed interface DecisionAnswer {
  data class Choice(
    val choice: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
  ) : DecisionAnswer

  data class Noul(val noul: Double) : DecisionAnswer

  data class Score(
    val score: Double,
    val probabilities: List<Double>,
    val confidence: Double,
    val legend: List<String>,
  ) : DecisionAnswer
}

data class DecisionAnswers(val model: String, val answers: Map<String, DecisionAnswer>)

fun interface DecisionModel {
  suspend fun evaluate(query: DecisionQuery): DecisionAnswers
}

data class ConfluenceEvaluation(
  val direction: StrategyDirection,
  val probabilities: Map<StrategyDirection, Double>,
  val confidence: Double,
)

object JevConfluence {
  fun query(evaluation: StrategyEvaluation): DecisionQuery {
    require(evaluation.rejection == null && evaluation.closedAtMs != null) {
      "Market evaluation unavailable"
    }
    return DecisionQuery(
      buildJsonObject {
        put("market", evaluation.key.marketId)
        put("venue", evaluation.key.venueId)
        put("network", evaluation.key.network)
        put("timeframe_ms", evaluation.key.timeframeMs)
        put("closed_at_ms", evaluation.closedAtMs)
        put(
          "indicators",
          buildJsonObject { evaluation.features.forEach { (name, value) -> put(name, value) } },
        )
        put(
          "interpretation",
          buildJsonObject {
            val facts = evaluation.features
            put(
              "macd_cross",
              when {
                facts.getValue("previousMacdHistogram") <= 0 &&
                  facts.getValue("macdHistogram") > 0 -> "up"
                facts.getValue("previousMacdHistogram") >= 0 &&
                  facts.getValue("macdHistogram") < 0 -> "down"
                else -> "none"
              },
            )
            put(
              "rsi_zone",
              when {
                facts.getValue("rsi") <= 30 -> "oversold"
                facts.getValue("rsi") >= 70 -> "overbought"
                else -> "neutral"
              },
            )
            put(
              "sma20_trend",
              when {
                facts.getValue("sma20") > facts.getValue("previousSma20") -> "rising"
                facts.getValue("sma20") < facts.getValue("previousSma20") -> "falling"
                else -> "flat"
              },
            )
            put(
              "price_vs_sma20",
              if (facts.getValue("close") > facts.getValue("sma20")) "above"
              else if (facts.getValue("close") < facts.getValue("sma20")) "below" else "at",
            )
            if (facts["bookFresh"] == 1.0) {
              put(
                "book_depth_bias",
                when {
                  facts.getValue("depthImbalance10") > 0 -> "more_bid_depth"
                  facts.getValue("depthImbalance10") < 0 -> "more_ask_depth"
                  else -> "balanced"
                },
              )
            }
            put("book", if (facts["bookFresh"] == 1.0) "fresh" else "unavailable_or_stale")
          },
        )
        put("baseline", evaluation.aggregate.name.lowercase())
        put(
          "signals",
          buildJsonObject {
            evaluation.signals.forEach { put(it.kind.name, it.direction.name.lowercase()) }
          },
        )
      },
      mapOf(
        "direction" to
          DecisionQuestion.Choice(
            "Assess optional directional confluence from the supplied closed-candle indicators and available fresh book metrics. Choose wait for conflicting or weak evidence. No execution, sizing, price prediction, or invented market facts.",
            mapOf(
              "buy" to "Indicators support upward directional confluence",
              "sell" to "Indicators support downward directional confluence",
              "wait" to "Evidence is weak or conflicting",
            ),
          )
      ),
    )
  }

  suspend fun evaluate(evaluation: StrategyEvaluation, model: DecisionModel): ConfluenceEvaluation {
    val response = model.evaluate(query(evaluation))
    require(response.model == "jev-1.13.0") { "Unexpected decision model" }
    val answer =
      response.answers["direction"] as? DecisionAnswer.Choice ?: error("Missing direction answer")
    require(
      answer.probabilities.keys == setOf("buy", "sell", "wait") &&
        answer.confidence.isFinite() &&
        answer.confidence in 0.0..1.0
    )
    require(
      answer.probabilities.values.all { it.isFinite() && it in 0.0..1.0 } &&
        kotlin.math.abs(answer.probabilities.values.sum() - 1.0) <= 0.001
    )
    require(answer.probabilities[answer.choice] == answer.probabilities.values.max())
    val direction =
      StrategyDirection.entries.firstOrNull { it.name.lowercase() == answer.choice }
        ?: error("Unknown direction")
    return ConfluenceEvaluation(
      direction,
      answer.probabilities.mapKeys { (key, _) -> StrategyDirection.valueOf(key.uppercase()) },
      answer.confidence,
    )
  }

  fun applyGate(
    signal: StrategySignal,
    confluence: ConfluenceEvaluation?,
    enabled: Boolean,
    minConfidence: Double = 0.6,
  ): StrategyDirection {
    require(minConfidence.isFinite() && minConfidence in 0.0..1.0)
    if (!enabled || signal.direction == StrategyDirection.WAIT) return signal.direction
    return if (
      confluence != null &&
        confluence.confidence >= minConfidence &&
        confluence.direction == signal.direction
    )
      signal.direction
    else StrategyDirection.WAIT
  }
}
