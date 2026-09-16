package xyz.mcxross.flare.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.decibel.DecibelNetwork

/** A single label/value rhythm for account, market, and transaction details. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().padding(vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      label,
      Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = FlareColors.TextSecondary,
    )
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
  HorizontalDivider(color = FlareColors.BorderSubtle)
}

@Composable
fun ActionRow(
  title: String,
  subtitle: String? = null,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth()
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(vertical = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (icon != null)
      Icon(icon, null, tint = FlareColors.TextSecondary, modifier = Modifier.size(22.dp))
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) FlareColors.TextPrimary else FlareColors.TextDisabled,
      )
      if (subtitle != null)
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
        )
    }
    Icon(
      Icons.AutoMirrored.Outlined.KeyboardArrowRight,
      null,
      tint = FlareColors.TextTertiary,
      modifier = Modifier.size(20.dp),
    )
  }
  HorizontalDivider(color = FlareColors.BorderSubtle)
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier) {
  Text(
    title,
    modifier.padding(top = 32.dp, bottom = 12.dp),
    style = MaterialTheme.typography.titleLarge,
  )
}

/** Addresses and hashes read the same everywhere they appear. */
fun shortAddress(address: String): String =
  if (address.length <= 18) address else address.take(10) + "…" + address.takeLast(6)

/**
 * Where a settled action can be inspected. Receipts link out instead of printing a hash, so the
 * record stays available without turning the screen into a log.
 */
class TransactionExplorer(private val network: DecibelNetwork) {
  fun url(hash: String): String =
    "https://explorer.aptoslabs.com/txn/$hash?network=${network.name.lowercase()}"
}

val LocalTransactionExplorer = staticCompositionLocalOf {
  TransactionExplorer(DecibelNetwork.TESTNET)
}

/** Shared completion state: a settled receipt has one unambiguous exit. */
@Composable
fun TransactionReceipt(message: String, hash: String, onDone: () -> Unit, enabled: Boolean = true) {
  val explorer = LocalTransactionExplorer.current
  val browser = LocalUriHandler.current
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Icon(
      Icons.Outlined.CheckCircle,
      contentDescription = null,
      tint = FlareColors.Positive,
      modifier = Modifier.size(48.dp),
    )
    Text(message, style = MaterialTheme.typography.bodyLarge)
    ActionRow(
      "View on Aptos Explorer",
      icon = Icons.AutoMirrored.Outlined.OpenInNew,
      onClick = { runCatching { browser.openUri(explorer.url(hash)) } },
    )
    FlareButton("Done", onDone, Modifier.fillMaxWidth(), enabled = enabled)
  }
}

@Composable
fun BackBar(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 56.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
    action?.invoke()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlareSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = FlareColors.Surface,
  ) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()
    ) {
      Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(bottom = 20.dp),
      )
      content()
    }
  }
}
