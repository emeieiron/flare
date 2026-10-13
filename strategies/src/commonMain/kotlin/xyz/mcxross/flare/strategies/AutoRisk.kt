package xyz.mcxross.flare.strategies

enum class AutoRunMode {
  SIMULATION,
  REAL,
}

data class AutoRunConfig(
  val strategy: StrategyKind,
  val mode: AutoRunMode,
  val orderSize: String,
  val maxPositionSize: String,
  val maxTotalNotional: String,
  val maxOrders: Int,
  val durationMinutes: Int,
  val cooldownSeconds: Int,
  val slippageBps: Int,
  val leverage: Int,
  val minConfidence: Double = 0.6,
)

data class AutoReservation(
  val size: String,
  val price: String,
  val notional: String,
  val projectedPosition: String,
  val nextConsumedNotional: String,
  val nextAttempts: Int,
)

data class AutoRiskDecision(
  val allowed: Boolean,
  val reason: String? = null,
  val reservation: AutoReservation? = null,
)

object AutoRisk {
  fun validate(config: AutoRunConfig): String? =
    try {
      require(ExactAmount.parse(config.orderSize).sign > 0) { "Order size must be positive" }
      require(ExactAmount.parse(config.maxPositionSize).sign > 0) {
        "Position limit must be positive"
      }
      require(ExactAmount.parse(config.maxTotalNotional).sign > 0) {
        "Notional budget must be positive"
      }
      require(config.maxOrders in 1..10_000) { "Order limit must be between 1 and 10000" }
      require(config.durationMinutes in 1..1440) { "Duration must be between 1 and 1440 minutes" }
      require(config.cooldownSeconds in 0..3600) { "Cooldown must be between 0 and 3600 seconds" }
      require(config.slippageBps in 0..1000) { "Slippage must be between 0 and 1000 bps" }
      require(config.leverage in 1..100) { "Leverage must be between 1 and 100" }
      require(config.mode != AutoRunMode.SIMULATION || config.leverage == 1) {
        "Paper simulation supports 1x only"
      }
      require(config.minConfidence.isFinite() && config.minConfidence in 0.0..1.0) {
        "Invalid confidence threshold"
      }
      null
    } catch (e: IllegalArgumentException) {
      e.message ?: "Invalid run configuration"
    } catch (_: IllegalStateException) {
      "Amount overflow"
    }

  fun check(
    config: AutoRunConfig,
    signedPosition: String,
    attempts: Int,
    consumedNotional: String,
    direction: StrategyDirection,
    price: String,
    allowShort: Boolean = true,
  ): AutoRiskDecision {
    validate(config)?.let {
      return AutoRiskDecision(false, it)
    }
    return try {
      require(direction != StrategyDirection.WAIT) { "No directional candidate" }
      require(attempts >= 0 && attempts < config.maxOrders) { "Order attempt limit reached" }
      val position = ExactAmount.parse(signedPosition)
      val consumed = ExactAmount.parse(consumedNotional)
      require(consumed.sign >= 0) { "Invalid consumed notional" }
      val quantity = ExactAmount.parse(config.orderSize)
      val limitPrice = ExactAmount.parse(price)
      require(limitPrice.sign > 0) { "Price must be positive" }
      val projected = position + if (direction == StrategyDirection.BUY) quantity else -quantity
      require(allowShort || projected.sign >= 0) { "Spot simulation cannot short" }
      val maximum = ExactAmount.parse(config.maxPositionSize)
      val reducingExisting =
        (projected.sign == 0 || projected.sign == position.sign) && projected.abs() < position.abs()
      require(projected.abs() <= maximum || reducingExisting) { "Position limit exceeded" }
      val notional = quantity * limitPrice
      val nextConsumed = consumed + notional
      require(nextConsumed <= ExactAmount.parse(config.maxTotalNotional)) {
        "Notional budget exceeded"
      }
      AutoRiskDecision(
        true,
        reservation =
          AutoReservation(
            quantity.toString(),
            limitPrice.toString(),
            notional.toString(),
            projected.toString(),
            nextConsumed.toString(),
            attempts + 1,
          ),
      )
    } catch (e: IllegalArgumentException) {
      AutoRiskDecision(false, e.message ?: "Invalid risk input")
    } catch (_: IllegalStateException) {
      AutoRiskDecision(false, "Amount overflow")
    }
  }
}
