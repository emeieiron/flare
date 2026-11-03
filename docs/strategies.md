# Strategies

[Back to Flare](../README.md)

## Strategy signals

Open a market, choose its chart timeframe, then tap **Strategies** and **Evaluate signals**. The sheet shows closed-candle signals and indicator evidence; refresh when a result expires. Signals use independent market reads and do not place orders. To trade, return to the market's **Trade** button, enter an amount and review the order.

Strategies work without an API key. To add optional confluence, open **Settings → Jev confluence**, save your Jev API key and explicitly enable it. The key stays in the device's secure vault; indicator requests go directly to Jev. If Jev is unavailable, independent signals remain visible without model approval. Model confidence and choice probabilities describe the model's answer, not a trade's likelihood of success. Calendar-month timeframes are unavailable because these strategies require fixed candle intervals.

## Auto trading

From **Strategies → Auto trading**, pick **Simulation** or **Real** and a strategy, then set order size, maximum position, order-value budget, attempts, duration, cooldown, slippage, leverage and the minimum Jev confidence under **Run limits**. The run decides once per newly closed candle in the market’s chart timeframe, and never twice on the same candle. Simulation fills against the live order book at 1× leverage with a 5 bps fee, excluding funding, network fees and liquidation.

With Jev confluence on, Jev is asked only when the strategy proposes a trade; an unavailable or low-confidence answer vetoes that trade. **Real** needs a review of the account and limits and one authorization. Each attempt reserves its worst-case order value before it’s sent through the market’s order rules and the transaction journal; a pending or uncertain order stays counted and is never resent. An opposite signal first reduces the open position with a reduce-only order.

During a real run, **Confirm transactions** in Settings is off and locked. Its previous setting comes back when the run pauses or ends, when Flare leaves the foreground, or on the next launch if the app was closed mid-run. **Pause** stops new orders and leaves positions open; resuming keeps the run’s limits, counters and reservations. Duration counts from the first start. Use **Reset for a new run** to clear them before changing configuration. The order-value budget excludes fees and is not a loss cap.

