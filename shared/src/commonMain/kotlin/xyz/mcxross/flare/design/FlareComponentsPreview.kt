package xyz.mcxross.flare.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview
@Composable
private fun FlareActionsPreview() {
  FlareTheme {
    Column(
      modifier = Modifier.fillMaxWidth().background(FlareColors.Canvas).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AssetHeader("BTC", "Bitcoin perpetual", "$62,500.25", "1.25%", positive = true)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlareChip("Cross", selected = true, onClick = {})
        IndicatorChip("RSI", selected = false, color = FlareColors.IndicatorCyan, onClick = {})
        CompactActionButton("Close", positive = false, onClick = {})
      }
      FlareButton("Deposit USDC", onClick = {}, modifier = Modifier.fillMaxWidth())
      BottomTradeDock(
        quantity = "0.01 BTC",
        enabled = true,
        onBuy = {},
        onSell = {},
        onQuantity = {},
      )
    }
  }
}

@Preview
@Composable
private fun FlareMarketRowPreview() {
  FlareTheme {
    Column(Modifier.fillMaxWidth().background(FlareColors.Canvas).padding(horizontal = 20.dp)) {
      FlareTopBar(title = "Markets", subtitle = "Decibel perpetuals")
      MarketListRow(
        symbol = "ETH",
        name = "Ethereum perpetual",
        price = "$4,318.42",
        delta = "2.08%",
        positive = false,
        favorite = true,
        onClick = {},
        onFavorite = {},
      )
    }
  }
}
