package xyz.mcxross.flare.strategies

data class PaperFill(
  val direction: StrategyDirection,
  val size: String,
  val price: String,
  val notional: String,
  val fee: String,
  val observedAtMs: Long,
)

data class PaperState(
  val positionSize: String = "0",
  val entryNotional: String = "0",
  val realizedPnl: String = "0",
  val feesPaid: String = "0",
  val filledNotional: String = "0",
  val attempts: Int = 0,
  val fills: List<PaperFill> = emptyList(),
)

data class PaperResult(
  val state: PaperState,
  val fill: PaperFill? = null,
  val rejection: String? = null,
)

/** 1x paper inventory; book spread and depth are observed, fees are a fixed per-fill assumption. */
class PaperSimulator(val feeBps: Int = 5, private val maxBookAgeMs: Long = 5000) {
  init {
    require(feeBps in 0..1000 && maxBookAgeMs > 0)
  }

  fun fill(
    config: AutoRunConfig,
    state: PaperState,
    snapshot: StrategyMarketSnapshot,
    direction: StrategyDirection,
    nowMs: Long,
    allowShort: Boolean = true,
  ): PaperResult {
    AutoRisk.validate(config)?.let {
      return PaperResult(state, rejection = it)
    }
    if (config.mode != AutoRunMode.SIMULATION)
      return PaperResult(state, rejection = "Paper fills require simulation mode")
    if (direction == StrategyDirection.WAIT)
      return PaperResult(state, rejection = "No directional candidate")
    return try {
      require(
        nowMs >= 0 &&
          snapshot.fetchedAtMs >= 0 &&
          snapshot.fetchedAtMs <= nowMs &&
          nowMs - snapshot.fetchedAtMs <= maxBookAgeMs
      ) {
        "Stale market snapshot"
      }
      val book = snapshot.book ?: error("Book unavailable")
      require(
        book.observedAtMs >= 0 &&
          book.observedAtMs <= nowMs &&
          nowMs - book.observedAtMs <= maxBookAgeMs
      ) {
        "Stale order book"
      }
      val position = ExactAmount.parse(state.positionSize)
      val basis = ExactAmount.parse(state.entryNotional)
      val realized = ExactAmount.parse(state.realizedPnl)
      val fees = ExactAmount.parse(state.feesPaid)
      val consumed = ExactAmount.parse(state.filledNotional)
      require(
        state.attempts >= 0 &&
          basis.sign >= 0 &&
          fees.sign >= 0 &&
          consumed.sign >= 0 &&
          (position.sign != 0 || basis.sign == 0) &&
          (position.sign == 0 || basis.sign > 0)
      ) {
        "Invalid paper ledger"
      }
      fun levels(
        input: List<StrategyBookLevel>,
        descending: Boolean,
      ): List<Pair<ExactAmount, ExactAmount>> {
        require(input.isNotEmpty()) { "Book depth unavailable" }
        val parsed = input.map { ExactAmount.parse(it.price) to ExactAmount.parse(it.size) }
        require(parsed.all { it.first.sign > 0 && it.second.sign > 0 }) { "Invalid book level" }
        require(
          parsed.zipWithNext().all { (a, b) ->
            if (descending) a.first > b.first else a.first < b.first
          }
        ) {
          "Unordered order book"
        }
        return parsed
      }
      val bids = levels(book.bids, true)
      val asks = levels(book.asks, false)
      require(bids.first().first < asks.first().first) { "Crossed order book" }
      val side = if (direction == StrategyDirection.BUY) asks else bids
      val quantity = ExactAmount.parse(config.orderSize)
      val slippage = ExactAmount.parse(config.slippageBps.toString()) * ExactAmount.parse("0.0001")
      val best = side.first().first
      val limit =
        best *
          (ExactAmount.parse("1") + if (direction == StrategyDirection.BUY) slippage else -slippage)
      var remaining = quantity
      var notional = ExactAmount.ZERO
      var worstPrice = best
      for ((price, available) in side) {
        if (if (direction == StrategyDirection.BUY) price > limit else price < limit) break
        val taken = minOf(remaining, available)
        notional += price * taken
        remaining -= taken
        if (price > worstPrice) worstPrice = price
        if (remaining.sign == 0) break
      }
      require(remaining.sign == 0) { "Insufficient depth within slippage limit" }
      val risk =
        AutoRisk.check(
          config,
          state.positionSize,
          state.attempts,
          state.filledNotional,
          direction,
          worstPrice.toString(),
          allowShort,
        )
      require(risk.allowed) { risk.reason ?: "Risk limit exceeded" }
      val signedSize = if (direction == StrategyDirection.BUY) quantity else -quantity
      val nextPosition = position + signedSize
      var nextBasis: ExactAmount
      var profit = ExactAmount.ZERO
      if (position.sign == 0 || position.sign == signedSize.sign) {
        nextBasis = basis + notional
      } else {
        val closing = minOf(position.abs(), quantity)
        val closedBasis =
          if (closing == position.abs()) basis else (basis * closing).divide(position.abs())
        val closedQuote =
          if (closing == quantity) notional else (notional * closing).divide(quantity)
        profit = if (position.sign > 0) closedQuote - closedBasis else closedBasis - closedQuote
        nextBasis =
          when {
            nextPosition.sign == 0 -> ExactAmount.ZERO
            nextPosition.sign == position.sign -> basis - closedBasis
            else -> notional - closedQuote
          }
      }
      val feeRate = ExactAmount.parse(feeBps.toString()) * ExactAmount.parse("0.0001")
      val fee = notional * feeRate
      val fill =
        PaperFill(
          direction,
          quantity.toString(),
          notional.divide(quantity).toString(),
          notional.toString(),
          fee.toString(),
          book.observedAtMs,
        )
      PaperResult(
        PaperState(
          nextPosition.toString(),
          nextBasis.toString(),
          (realized + profit - fee).toString(),
          (fees + fee).toString(),
          (consumed + notional).toString(),
          state.attempts + 1,
          state.fills + fill,
        ),
        fill,
      )
    } catch (e: IllegalArgumentException) {
      PaperResult(state, rejection = e.message ?: "Paper fill rejected")
    } catch (e: IllegalStateException) {
      PaperResult(
        state,
        rejection = if (e.message == "Book unavailable") "Book unavailable" else "Amount overflow",
      )
    }
  }

  fun unrealizedPnl(state: PaperState, markPrice: String): String {
    val position = ExactAmount.parse(state.positionSize)
    val price = ExactAmount.parse(markPrice)
    require(price.sign > 0) { "Mark price must be positive" }
    val currentNotional = position.abs() * price
    val basis = ExactAmount.parse(state.entryNotional)
    return when {
      position.sign > 0 -> currentNotional - basis
      position.sign < 0 -> basis - currentNotional
      else -> ExactAmount.ZERO
    }.toString()
  }
}
