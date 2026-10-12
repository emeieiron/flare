package xyz.mcxross.flare.testing

import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderRules
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType

/** Accepts every order, pricing a market order at the book's best level. */
object AcceptingOrderRules : OrderRules {
  override val minimumSize: String? = null

  override fun check(order: OrderRequest, orderBook: OrderBook?): OrderCheck =
    OrderCheck.Valid(
      if (order.type == OrderType.MARKET) {
        order.copy(price = if (order.side == OrderSide.BUY) orderBook?.bestAsk else orderBook?.bestBid)
      } else order
    )

  override fun checkExitPrices(takeProfit: String?, stopLoss: String?): String? = null
}
