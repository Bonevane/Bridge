package com.bonevane.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bonevane.bridge.Prefs
import com.bonevane.bridge.TunnelService
import com.bonevane.bridge.TunnelState

/**
 * Bridge's phone screen, in the Material 3 Expressive idiom: a large title that
 * collapses on scroll, one hero card carrying the current state and the main
 * action, then grouped cards for the ticket, readiness and settings. Colours
 * come from the wallpaper (see [BridgeTheme]), corners are generous, and the
 * type is large and heavy so state is readable at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    onToggleTunnel: () -> Unit,
    onCopyTicket: () -> Unit,
    onShareTicket: () -> Unit,
    onGetReady: () -> Unit,
    onPause: () -> Unit,
    onLockDown: () -> Unit,
    onKeepReadyChange: (Boolean) -> Unit,
    onAutostartChange: (Boolean) -> Unit,
    onBatteryExemption: () -> Unit,
    onNewIdentity: () -> Unit,
    onGrantNotificationAccess: () -> Unit,
    isIgnoringBatteryOptimisations: () -> Boolean,
    notificationAccess: () -> Boolean,
) {
    val context = LocalContext.current
    // TunnelState is a plain observable object shared with the service; this
    // turns its listener into Compose state.
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener: () -> Unit = { tick.intValue++ }
        TunnelState.addListener(listener)
        onDispose { TunnelState.removeListener(listener) }
    }
    tick.intValue   // read it so a listener callback recomposes this screen

    val running = TunnelState.tunnelOn
    val ready = TunnelState.ready
    val status = TunnelState.status
    val ticket = TunnelState.ticket ?: Prefs.ticket(context)
    var keepReady by remember { mutableStateOf(Prefs.keepReady(context)) }
    var autostart by remember { mutableStateOf(Prefs.autostart(context)) }
    var showLog by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // A compact centred bar: the large collapsing one wasted most of the first
    // screen on empty space before anything useful appeared.
    val appBar = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(appBar.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    // A little air around the title: flush against the status bar
                    // and the first card, it felt cramped.
                    Text(
                        "Bridge",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                },
                scrollBehavior = appBar,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            HeroCard(running = running, ready = ready, status = status, onToggle = onToggleTunnel)

            TicketCard(ticket = ticket, onCopy = onCopyTicket, onShare = onShareTicket)

            SectionCard(title = "Readiness") {
                Text(
                    if (keepReady)
                        "USB debugging stays on between sessions, so your Mac can connect from anywhere, including cellular. Pause it when a banking app complains."
                    else
                        "USB debugging is turned off after each session. From cellular you can only connect if the last disconnect was on cellular too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    label = "Keep ready after disconnect",
                    checked = keepReady,
                    onCheckedChange = { keepReady = it; onKeepReadyChange(it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onGetReady, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Get ready")
                    }
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PauseCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause 15m")
                    }
                }
                OutlinedButton(onClick = onLockDown, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Turn USB debugging off now")
                }
            }

            SectionCard(title = "Notifications") {
                val granted = notificationAccess()
                Text(
                    if (granted) "Your notifications appear on the Mac while it's connected."
                    else "Let Bridge read notifications to show them on your Mac. This works even when USB debugging is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted) {
                    FilledTonalButton(onClick = onGrantNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Notifications, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Allow notification access")
                    }
                }
            }

            SectionCard(title = "Settings") {
                SwitchRow(
                    label = "Start automatically after reboot",
                    checked = autostart,
                    onCheckedChange = { autostart = it; onAutostartChange(it) },
                )
                if (!isIgnoringBatteryOptimisations()) {
                    FilledTonalButton(onClick = onBatteryExemption, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow Bridge to run in the background")
                    }
                }
                OutlinedButton(onClick = onNewIdentity, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create a new identity")
                }
            }

            LogCard(expanded = showLog, onToggle = { showLog = !showLog })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeroCard(running: Boolean, ready: Boolean, status: String, onToggle: () -> Unit) {
    // The hero colour carries the state: green-ish "ready" uses the primary
    // container, anything else stays neutral.
    val container by animateColorAsState(
        if (ready) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "hero",
    )
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    ready -> "Ready"
                    running -> "Starting"
                    else -> "Nearby only"
                },
                style = MaterialTheme.typography.displaySmall,
            )
            Text(status, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onToggle,
                shape = RoundedCornerShape(20.dp),
                contentPadding = ButtonDefaults.ContentPadding,
                // Inverted against the hero's own colour, so the main action
                // stays high-contrast whatever the wallpaper palette is.
                colors = if (ready) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.primaryContainer,
                ) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Turn tunnel off" else "Turn tunnel on", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun TicketCard(ticket: String?, onCopy: () -> Unit, onShare: () -> Unit) {
    SectionCard(title = "Ticket") {
        Text(
            ticket ?: "No ticket yet. Start the tunnel.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onCopy, enabled = ticket != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            FilledTonalButton(onClick = onShare, enabled = ticket != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        }
        Text(
            "Your Mac needs this once. Easiest: plug in and click \"Set up over USB\" there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogCard(expanded: Boolean, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Toggle log")
                }
            }
            AnimatedVisibility(expanded) {
                Text(
                    TunnelState.logText().takeLast(4000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** A rounded, tonal container with a title: the building block of the screen. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
