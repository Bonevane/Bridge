package com.bonevane.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bonevane.bridge.Access
import com.bonevane.bridge.DaemonManager
import com.bonevane.bridge.NotificationFilter
import com.bonevane.bridge.Prefs
import com.bonevane.bridge.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The phone can be in one of three modes, and the whole screen is built
 * around making that obvious:
 *  - [OFF]: nothing runs. No Bluetooth, no tunnel, no battery cost.
 *  - [NEARBY]: Bluetooth only. Notifications and clipboard reach a Mac in the
 *    same room; costs nothing on the network.
 *  - [ANYWHERE]: Bluetooth plus the internet tunnel, so the Mac can mirror the
 *    phone from any network.
 */
enum class Mode { OFF, NEARBY, ANYWHERE }

/**
 * Bridge's phone screen. Material 3 Expressive the way apps like Wavelet do
 * it: a hero carrying the state, then sections whose titles sit *outside* the
 * group in the accent colour, and whose rows are separate tiles with a hairline
 * of background between them rather than one big card with dividers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    onModeChange: (Mode) -> Unit,
    onCopyTicket: () -> Unit,
    onGetReady: () -> Unit,
    onPause: () -> Unit,
    onLockDown: () -> Unit,
    onKeepReadyChange: (Boolean) -> Unit,
    onAutostartChange: (Boolean) -> Unit,
    onQuietStatusChange: (Boolean) -> Unit,
    onBatteryExemption: () -> Unit,
    onNewIdentity: () -> Unit,
    onGrantAccess: (Access) -> Unit,
    onOpenLink: (String) -> Unit,
    version: String,
    isIgnoringBatteryOptimisations: () -> Boolean,
    accessItems: List<Access>,
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

    val mode = when {
        !TunnelState.running -> Mode.OFF
        TunnelState.tunnelOn -> Mode.ANYWHERE
        else -> Mode.NEARBY
    }
    val ticket = TunnelState.ticket ?: Prefs.ticket(context)
    var keepReady by remember { mutableStateOf(Prefs.keepReady(context)) }
    var autostart by remember { mutableStateOf(Prefs.autostart(context)) }
    var quietStatus by remember { mutableStateOf(Prefs.quietStatus(context)) }
    var showLog by remember { mutableStateOf(false) }

    // The helper is a shell-uid process; the only way to know it's alive is to
    // knock on its port. Polled while the screen is showing, off the main thread.
    var helperAlive by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(tick.intValue) {
        while (true) {
            helperAlive = withContext(Dispatchers.IO) { DaemonManager.isDaemonAlive() }
            delay(5_000)
        }
    }

    val scroll = rememberScrollState()
    val appBar = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(appBar.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Bridge",
                        style = MaterialTheme.typography.headlineSmall,
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            HeroCard(
                mode = mode,
                status = TunnelState.status,
                macLinked = TunnelState.macLinked,
                helperAlive = helperAlive,
                onModeChange = onModeChange,
            )

            val setupDone = Prefs.setupDone(context)
            if (!setupDone) {
                GettingStarted(accessItems = accessItems, ticket = ticket, onGrantAccess = onGrantAccess)
            } else if (keepReady && helperAlive == false) {
                // Typical after a reboot: the setting says "ready", but the
                // helper died with the phone and can't come back by itself
                // without Wi-Fi. Say so, instead of leaving "Ready" on screen.
                Notice(
                    icon = Icons.Rounded.RestartAlt,
                    title = "The helper isn't running",
                    body = "Usually because the phone restarted. It comes back on its own on Wi-Fi, " +
                        "or plug into the Mac and use Set Up Over USB.",
                    action = "Start it now" to onGetReady,
                )
            }

            Section("Ticket") {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                           verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            ticket ?: "No ticket yet. Turn on Anywhere to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = onCopyTicket, enabled = ticket != null, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Text(
                            "This is a key to your phone, not an address. Don't share it: paste it only into Bridge " +
                                "on your own Mac. Set Up Over USB does this for you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("Readiness") {
                item {
                    SwitchRow(
                        icon = Icons.Rounded.Bolt,
                        label = "Keep ready after disconnect",
                        detail = if (keepReady) "USB debugging stays on between sessions, so the Mac can connect from anywhere, including cellular."
                                 else "USB debugging turns off after each session. Starting again needs Wi-Fi.",
                        checked = keepReady,
                        onCheckedChange = { keepReady = it; onKeepReadyChange(it) },
                    )
                }
                item { ActionRow(Icons.Rounded.Laptop, "Get ready now", "Start the helper so the Mac can connect", onGetReady) }
                item { ActionRow(Icons.Rounded.PauseCircle, "Pause for 15 minutes", "USB debugging off while a banking app runs", onPause) }
                item { ActionRow(Icons.Rounded.Lock, "Turn USB debugging off now", null, onLockDown) }
            }

            Section("Permissions") {
                accessItems.forEach { access ->
                    item { AccessRow(item = access, onGrant = { onGrantAccess(access) }) }
                }
            }

            NotificationsSection(tick = tick.intValue)

            Section("Settings") {
                item {
                    SwitchRow(Icons.Rounded.RestartAlt, "Start after reboot", null, autostart) {
                        autostart = it; onAutostartChange(it)
                    }
                }
                item {
                    SwitchRow(
                        Icons.Rounded.NotificationsOff, "Quiet status notification",
                        "Keeps the \"Bridge is on\" notification out of the status bar",
                        quietStatus,
                    ) { quietStatus = it; onQuietStatusChange(it) }
                }
                if (!isIgnoringBatteryOptimisations()) {
                    item { ActionRow(Icons.Rounded.Bolt, "Allow running in the background", "Stops Android suspending Bridge", onBatteryExemption) }
                }
                item { ActionRow(Icons.Rounded.Key, "Create a new identity", "Invalidates the ticket; set up over USB again", onNewIdentity) }
            }

            Section("Log") {
                item {
                    Column {
                        ActionRow(
                            icon = null, label = "Activity log", detail = null,
                            trailing = if (showLog) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            onClick = { showLog = !showLog },
                        )
                        AnimatedVisibility(showLog) {
                            Text(
                                TunnelState.logText().takeLast(4000),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }

            AboutFooter(version = version, onOpen = onOpenLink)
            Spacer(Modifier.height(12.dp))
        }
    }
}

// MARK: - Hero

@Composable
private fun HeroCard(
    mode: Mode,
    status: String,
    macLinked: Boolean,
    helperAlive: Boolean?,
    onModeChange: (Mode) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Each mode has its own colour, so a glance at the card says which one it is.
    val container by animateColorAsState(
        when (mode) {
            Mode.OFF -> scheme.surfaceContainerHigh
            Mode.NEARBY -> scheme.secondaryContainer
            Mode.ANYWHERE -> scheme.primaryContainer
        }, label = "hero",
    )
    val onContainer = when (mode) {
        Mode.OFF -> scheme.onSurface
        Mode.NEARBY -> scheme.onSecondaryContainer
        Mode.ANYWHERE -> scheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = onContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (mode) {
                    Mode.OFF -> "Off"
                    Mode.NEARBY -> "Nearby"
                    Mode.ANYWHERE -> "Anywhere"
                },
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                when (mode) {
                    Mode.OFF -> "Nothing is running."
                    Mode.NEARBY -> "Bluetooth only: notifications and clipboard reach a Mac in the same room."
                    Mode.ANYWHERE -> status
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (mode != Mode.OFF) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(
                        icon = Icons.Rounded.Laptop,
                        text = if (macLinked) "Mac linked" else "No Mac nearby",
                        on = macLinked,
                    )
                    if (mode == Mode.ANYWHERE) StatusChip(
                        icon = Icons.Rounded.Bolt,
                        text = when (helperAlive) { true -> "Helper running"; false -> "Helper off"; null -> "Checking…" },
                        on = helperAlive == true,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(
                    Triple(Mode.OFF, "Off", Icons.Rounded.BluetoothDisabled),
                    Triple(Mode.NEARBY, "Nearby", Icons.Rounded.Bluetooth),
                    Triple(Mode.ANYWHERE, "Anywhere", Icons.Rounded.Public),
                )
                modes.forEachIndexed { i, (m, label, icon) ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { if (mode != m) onModeChange(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        // The default icon slot swaps in a checkmark and sits it
                        // on its own baseline, which never lines up with the
                        // label; one centred row with the mode's own icon does.
                        icon = {},
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = scheme.surface,
                            activeContentColor = scheme.onSurface,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = onContainer,
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(icon, null, Modifier.size(16.dp))
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, on: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (on) 0.9f else 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// MARK: - First run

/**
 * Shown until the phone has a ticket and the USB-granted permission. Two steps,
 * each ticked off as it happens, so a new user isn't left reading the whole
 * screen to work out what to do first.
 */
@Composable
private fun GettingStarted(accessItems: List<Access>, ticket: String?, onGrantAccess: (Access) -> Unit) {
    val grantable = accessItems.filter { it.intent != null || it.runtimePermissions.isNotEmpty() }
    val permissionsDone = grantable.all { it.granted }
    Section("Getting started") {
        item {
            StepRow(
                number = 1, done = permissionsDone,
                title = "Allow what Bridge needs",
                body = if (permissionsDone) "All set." else "Tap each item under Permissions below.",
                onClick = grantable.firstOrNull { !it.granted }?.let { a -> { onGrantAccess(a) } },
            )
        }
        item {
            StepRow(
                number = 2, done = ticket != null,
                title = "Plug into your Mac once",
                body = "Open Bridge on the Mac and click Set Up Over USB. It reads the ticket and grants " +
                    "the one permission Android won't let you tap.",
                onClick = null,
            )
        }
    }
}

@Composable
private fun StepRow(number: Int, done: Boolean, title: String, body: String, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("$number · $title", style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Notice(icon: ImageVector, title: String, body: String, action: Pair<String, () -> Unit>) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = action.second) { Text(action.first) }
        }
    }
}

// MARK: - Notifications

/**
 * The per-app list can get long, so it lives behind one row that shows how
 * many apps there are and opens on tap.
 */
@Composable
private fun NotificationsSection(tick: Int) {
    val context = LocalContext.current
    var skipSilent by remember { mutableStateOf(Prefs.skipSilent(context)) }
    var skipOpen by remember { mutableStateOf(Prefs.skipOpenOnMac(context)) }
    var showApps by remember { mutableStateOf(false) }
    val apps = remember(tick) { Prefs.seenApps(context).toList().sortedBy { it.second.lowercase() } }
    val openOnMac = NotificationFilter.openOnMac

    Section("Notifications on the Mac") {
        item {
            SwitchRow(Icons.Rounded.VolumeOff, "Skip silent notifications",
                      "Ones Android showed without a sound or a peek", skipSilent) {
                skipSilent = it; Prefs.setSkipSilent(context, it)
            }
        }
        item {
            SwitchRow(Icons.Rounded.Laptop, "Skip apps open on the Mac",
                      "WhatsApp on the Mac already shows its own", skipOpen) {
                skipOpen = it; Prefs.setSkipOpenOnMac(context, it)
            }
        }
        item {
            Column {
                ActionRow(
                    icon = Icons.Rounded.Apps,
                    label = if (apps.isEmpty()) "Apps" else "Apps · ${apps.size}",
                    detail = if (apps.isEmpty()) "Apps appear here once they've sent a notification"
                             else "Switch off any you don't want on the Mac",
                    trailing = if (showApps) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    onClick = { showApps = !showApps },
                )
                AnimatedVisibility(showApps) {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        apps.forEach { (pkg, label) ->
                            var on by remember(pkg) { mutableStateOf(Prefs.appMirrored(context, pkg)) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    if (pkg in openOnMac) Text(
                                        "Open on your Mac",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Switch(checked = on, onCheckedChange = { on = it; Prefs.setAppMirrored(context, pkg, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Rows

/** One permission: what it's for, whether it's granted, and a way to grant it. */
@Composable
private fun AccessRow(item: Access, onGrant: () -> Unit) {
    val clickable = item.intent != null || item.runtimePermissions.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onGrant) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (item.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text(item.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!item.granted && !clickable) {
            Icon(Icons.Rounded.Cable, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        } else if (clickable) {
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector?,
    label: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector?,
    label: String,
    detail: String?,
    onClick: () -> Unit,
    trailing: ImageVector = Icons.Rounded.ChevronRight,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(trailing, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// MARK: - Sections

/** Collects a section's rows so [Section] can shape each one by its position. */
class SectionScope {
    val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) { items.add(content) }
}

/**
 * A titled group in the Wavelet style: the title sits above the group in the
 * accent colour, and every row is its own tile. The outer corners of the
 * group are large; the corners between rows are small, so the 2 dp gap reads
 * as a seam rather than as separate cards.
 */
@Composable
private fun Section(title: String, content: SectionScope.() -> Unit) {
    val rows = SectionScope().apply(content).items
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        rows.forEachIndexed { i, row ->
            val big = 24.dp
            val small = 6.dp
            val shape = RoundedCornerShape(
                topStart = if (i == 0) big else small,
                topEnd = if (i == 0) big else small,
                bottomStart = if (i == rows.lastIndex) big else small,
                bottomEnd = if (i == rows.lastIndex) big else small,
            )
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) { row() }
        }
    }
}

// MARK: - Footer

@Composable
private fun AboutFooter(version: String, onOpen: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Text("Bridge $version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Made by", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Bonevane", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.clickable { onOpen("https://github.com/Bonevane") })
            Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("bonevane.vercel.app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.clickable { onOpen("https://bonevane.vercel.app") })
        }
    }
}
