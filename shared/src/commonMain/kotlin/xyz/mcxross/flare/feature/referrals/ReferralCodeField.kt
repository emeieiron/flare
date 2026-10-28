package xyz.mcxross.flare.feature.referrals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.FlareTextField
import xyz.mcxross.flare.design.rememberFlareShimmer

@Composable
fun ReferralCodeField(
  state: ReferralUiState,
  onCodeChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  optional: Boolean = false,
  explainIneligible: Boolean = false,
) {
  val readOnlyCode = when {
    state.pending -> state.appliedCode ?: state.input
    state.eligibility == ReferralEligibility.BOUND -> state.appliedCode.orEmpty()
    else -> null
  }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (optional) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Referral code", style = MaterialTheme.typography.labelMedium, color = FlareColors.TextSecondary)
        Text("Optional", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
          color = FlareColors.TextTertiary)
      }
    }
    if (explainIneligible && state.eligibility == ReferralEligibility.INELIGIBLE) {
      Text("No referral code", style = MaterialTheme.typography.bodyMedium, color = FlareColors.TextSecondary)
      Text("Codes can only be applied before your first deposit or trade.",
        style = MaterialTheme.typography.bodySmall, color = FlareColors.TextTertiary)
    } else if (readOnlyCode != null) {
      FlareTextField(readOnlyCode, {}, Modifier.fillMaxWidth(), label = if (optional) null else "Referral code",
        readOnly = true)
      if (state.pending) {
        Text("Referral confirmation pending", style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary)
      }
    } else {
      FlareTextField(
        value = state.input,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth(),
        label = if (optional) null else "Referral code",
        placeholder = "Enter a code",
        enabled = state.editable,
        trailingIcon = if (state.input.isNotEmpty() && state.editable) {
          {
            Box(
              Modifier.size(48.dp)
                .clickable(role = Role.Button, onClickLabel = "Clear referral code") { onCodeChange("") }
                .semantics { contentDescription = "Clear referral code" },
              contentAlignment = Alignment.Center,
            ) {
              Icon(FlareIcons.Close, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = FlareColors.TextTertiary)
            }
          }
        } else null,
      )
      if (state.validating) {
        val shimmer = rememberFlareShimmer()
        FlareSkeletonBox(
          Modifier.fillMaxWidth(0.42f).padding(top = 2.dp).shimmer(shimmer)
            .semantics { contentDescription = "Checking referral code" }
        )
      }
    }
    if (state.input.isNotBlank() || readOnlyCode?.isNotBlank() == true) {
      Text(if ((readOnlyCode ?: state.input).trim() == state.suggestedCode) "Flare may earn referral rewards." else "Your referrer may earn rewards.", style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextTertiary)
    }
    state.error?.takeUnless { state.pending && it == "Referral confirmation pending" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FlareColors.Negative) }
  }
}
