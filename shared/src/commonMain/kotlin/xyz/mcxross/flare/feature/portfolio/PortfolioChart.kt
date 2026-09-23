package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors

@Composable
fun PortfolioPerformanceChart(
  points: List<PortfolioChartPoint>,
  range: PortfolioChartRange,
  metric: PortfolioMetric,
  onRangeSelect: (PortfolioChartRange) -> Unit,
  onMetricSelect: (PortfolioMetric) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(FlareColors.Surface, RoundedCornerShape(16.dp))
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PortfolioMetric.entries.forEach { m ->
          FlareChip(
            text = m.label,
            selected = metric == m,
            onClick = { onMetricSelect(m) },
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PortfolioChartRange.entries.forEach { r ->
          FlareChip(
            text = r.label,
            selected = range == r,
            onClick = { onRangeSelect(r) },
          )
        }
      }
    }

    if (points.size >= 2) {
      val values = points.map { pt ->
        when (metric) {
          PortfolioMetric.EQUITY -> pt.accountValue ?: pt.value
          PortfolioMetric.PNL -> pt.realizedPnl ?: pt.value
        }
      }
      val isPositive = values.last() >= values.first()
      val lineColor = if (isPositive) FlareColors.Positive else FlareColors.Negative

      val model =
        remember(points, metric) {
          CartesianChartModel(
            LineCartesianLayerModel.build {
              series(x = points.indices.toList(), y = values)
            }
          )
        }

      val marker =
        rememberDefaultCartesianMarker(
          label =
            rememberTextComponent(
              style = MaterialTheme.typography.bodySmall.copy(color = FlareColors.TextPrimary)
            ),
          guideline = rememberLineComponent(fill = Fill(FlareColors.BorderStrong), thickness = 1.dp),
        )

      CartesianChartHost(
        chart =
          rememberCartesianChart(
            rememberLineCartesianLayer(
              lineProvider =
                LineCartesianLayer.LineProvider.series(
                  LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(lineColor)))
                )
            ),
            marker = marker,
            bottomAxis = HorizontalAxis.rememberBottom(label = null, tick = null, guideline = null),
            endAxis = VerticalAxis.rememberEnd(label = null, tick = null, guideline = null),
          ),
        model = model,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        modifier = Modifier.fillMaxWidth().height(160.dp),
      )
    } else {
      Box(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Performance history building as you trade",
          color = FlareColors.TextTertiary,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
fun TradingRewardsCard(
  streak: TradingStreak?,
  amps: AmpsBreakdown?,
  tier: TierInfo?,
  modifier: Modifier = Modifier,
) {
  if (streak == null && amps == null && tier == null) return

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(FlareColors.Elevated, RoundedCornerShape(14.dp))
        .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val streakCount = streak?.streakCount ?: 0
        Text(
          text = if (streakCount > 0) "🔥 $streakCount-Day Streak" else "🔥 Start Streak",
          style = MaterialTheme.typography.labelLarge,
          color = if (streakCount > 0) FlareColors.Positive else FlareColors.TextPrimary,
        )
        streak?.graceDaysRemaining?.takeIf { it > 0 }?.let { grace ->
          Text(
            "· $grace grace left",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
          )
        }
      }
      Text(
        text = tier?.let { "${it.tier} Tier" } ?: "Trade daily to maintain streak",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
      )
    }

    amps?.let {
      Column(horizontalAlignment = Alignment.End) {
        Text(
          text = "⚡ ${formatBalance(it.totalAmps)} Amps",
          style = MaterialTheme.typography.labelLarge,
          color = FlareColors.Positive,
        )
        it.rank?.let { rank ->
          Text(
            text = "Rank #$rank",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextTertiary,
          )
        }
      }
    }
  }
}
