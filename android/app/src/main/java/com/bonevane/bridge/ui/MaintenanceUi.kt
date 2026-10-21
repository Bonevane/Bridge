@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.bonevane.bridge.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bonevane.bridge.Health
import com.bonevane.bridge.KeyKeeper
import com.bonevane.bridge.Prefs
import com.bonevane.bridge.Updater
import com.bonevane.bridge.Updater.State

// The phone's half of "tell me before it breaks" and "keep it up to date".
// Built from the same pieces as the rest of the screen: a Notice-style card
// for anything that needs attention now, and Section rows for settings.

/** Problems the rest of the screen doesn't already show (Permissions covers battery, Autostart and access). */
private val SHOWN_HERE = setOf("nogrant", "keylost", "keyexp", "keycheck", "nolistener")

/**
 * What's wrong right now, worst first. Material 3 Expressive: the worst
 * problem large, its icon in a cookie shape, one shape-morphing button that
 * fixes it; the others as smaller lines on a tinted inset. Red when
 * something is broken, the tertiary "soon" colour when it's about to be.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProblemsCard(tick: Int) {
    val context = LocalContext.current
    val issues = remember(tick) { Health.issues(context).filter { it.code.substringBefore(':') in SHOWN_HERE } }
    if (issues.isEmpty()) return
    val top = issues.first()
    val error = top.level == Health.Level.ERROR
    val scheme = MaterialTheme.colorScheme
    val accent = if (error) scheme.error else scheme.tertiary
    val onAccent = if (error) scheme.onError else scheme.onTertiary
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (error) scheme.errorContainer else scheme.tertiaryContainer,
        contentColor = if (error) scheme.onErrorContainer else scheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeBadge(MaterialShapes.Cookie9Sided, iconFor(top), accent, onAccent)
                Text(top.title, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
            }
            Text(top.detail, style = MaterialTheme.typography.bodyMedium)
            top.fix?.let { fix ->
                Button(
                    onClick = { runFix(context, fix) },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                ) { Text(fix.label) }
            }
            if (issues.size > 1) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.surface.copy(alpha = 0.55f),
                    contentColor = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        issues.drop(1).forEach { other ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            ) {
                                Icon(iconFor(other), null, Modifier.size(20.dp),
                                     tint = if (other.level == Health.Level.ERROR) scheme.error else scheme.tertiary)
                                Spacer(Modifier.width(12.dp))
                                Text(other.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                other.fix?.let { fix ->
                                    TextButton(onClick = { runFix(context, fix) }, shapes = ButtonDefaults.shapes()) { Text(fix.label) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An icon on one of Material 3 Expressive's shapes (cookie, burst…). */
@Composable
private fun ShapeBadge(
    polygon: androidx.graphics.shapes.RoundedPolygon,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    content: Color,
    size: androidx.compose.ui.unit.Dp = 48.dp,
) {
    Box(
        Modifier.size(size).clip(polygon.toShape()).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

private fun iconFor(issue: Health.Issue) = when {
    issue.code == "keylost" || issue.code == "nogrant" -> Icons.Rounded.KeyOff
    issue.level == Health.Level.ERROR -> Icons.Rounded.ErrorOutline
    else -> Icons.Rounded.WarningAmber
}

private fun runFix(context: android.content.Context, fix: Health.Fix) {
    when (fix) {
        Health.Fix.RENEW, Health.Fix.CHECK_KEY -> {
            val started = KeyKeeper.renew(context, reason = "asked on the phone")
            Toast.makeText(context,
                if (!started) "Already checking" else if (KeyKeeper.currentWifi(context) == null) "Bridge needs Wi-Fi for this" else "Checking…",
                Toast.LENGTH_SHORT).show()
        }
        else -> Health.intentFor(context, fix)?.let { intent ->
            runCatching { context.startActivity(intent) }
                .recoverCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
        }
    }
}

// MARK: - The update card

/**
 * Shows only while an update is in play: available, downloading, waiting,
 * installing, or stuck. Material 3 Expressive: a burst-shaped badge, the
 * percentage large over a wavy progress bar (flat while paused), the
 * morphing loading indicator while it checks or installs, and
 * shape-morphing buttons. "Later" hides it until the app is next opened;
 * the Updates section still lists the version.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateCard(tick: Int) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = tick
    val state = Updater.state
    val release = Updater.release() ?: return
    var later by remember { mutableStateOf("") }
    if (state is State.Available && later == release.version) return
    var showNotes by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    val failed = state is State.Failed
    val accent = if (failed) scheme.error else scheme.primary
    val onAccent = if (failed) scheme.onError else scheme.onPrimary
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (failed) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (failed) scheme.onErrorContainer else scheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeBadge(if (failed) MaterialShapes.Cookie9Sided else MaterialShapes.SoftBurst,
                           if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.SystemUpdate, accent, onAccent)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            is State.Downloading -> "Downloading"
                            is State.Paused -> "Paused"
                            is State.Verifying -> "Checking the download"
                            is State.Installing, is State.Confirming -> "Installing"
                            is State.Failed -> "Didn't install"
                            else -> "Update available"
                        },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    Text("Bridge ${release.version} · you have ${Updater.currentVersion(context)}",
                         style = MaterialTheme.typography.labelLarge)
                }
            }

            when (state) {
                is State.Downloading, is State.Paused -> {
                    val done = if (state is State.Downloading) state.done else (state as State.Paused).done
                    val total = if (state is State.Downloading) state.total else (state as State.Paused).total
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.headlineMediumEmphasized)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${mb(done)} of ${mb(total)}" +
                                if (state is State.Downloading && state.perSecond > 0) " · ${mb(state.perSecond)}/s" else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    LinearWavyProgressIndicator(
                        progress = { fraction },
                        // The wave is the motion; a paused download lies flat.
                        amplitude = { if (state is State.Downloading) 1f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state is State.Paused && state.reason != null) {
                        Text(state.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is State.Verifying, is State.Installing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text(
                        if (state is State.Verifying) "Making sure it's intact and really Bridge…"
                        else "Bridge stops, updates and starts again by itself in a few seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is State.Confirming -> Text("Confirm the install in the window Android shows. Bridge starts again by itself afterwards.", style = MaterialTheme.typography.bodyMedium)
                is State.NeedsPermission -> Text("Allow Bridge to install apps, then come back and tap Install. It's only used for Bridge's own updates.", style = MaterialTheme.typography.bodyMedium)
                is State.Failed -> Text(state.message, style = MaterialTheme.typography.bodyMedium)
                else -> Text("Installing restarts Bridge for a few seconds; it comes back in the same mode.", style = MaterialTheme.typography.bodyMedium)
            }

            if (release.notes.isNotBlank()) {
                TextButton(
                    onClick = { showNotes = !showNotes },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) {
                    Text(if (showNotes) "Hide what's new" else "What's new")
                }
                AnimatedVisibility(showNotes) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = scheme.surface.copy(alpha = 0.55f),
                        contentColor = scheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(plainNotes(release.notes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun Primary(label: String, onClick: () -> Unit) = Button(
                    onClick = onClick, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                ) { Text(label) }
                @Composable fun Secondary(label: String, onClick: () -> Unit) =
                    OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(label) }
                when (state) {
                    is State.Available -> { Primary("Update") { Updater.start(context) }; Secondary("Later") { later = release.version } }
                    is State.Downloading -> { Primary("Pause") { Updater.pause(context) }; Secondary("Cancel") { Updater.cancel(context) } }
                    is State.Paused -> { Primary("Resume") { Updater.start(context) }; Secondary("Cancel") { Updater.cancel(context) } }
                    is State.NeedsPermission -> {
                        Primary("Allow") {
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")))
                            }
                        }
                        Secondary("Install") { Updater.start(context) }
                    }
                    is State.Confirming -> Secondary("Show it again") { Updater.start(context) }
                    is State.Failed -> { Primary("Try again") { Updater.start(context) }; Secondary("Discard") { Updater.cancel(context) } }
                    else -> {}
                }
            }
        }
    }
}

private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

/** Release notes are Markdown; on the phone they read better without the markup. */
private fun plainNotes(md: String): String = md
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("(?m)^#+\\s*"), "")
    .replace(Regex("(?m)^[*-] "), "• ")
    .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
    .take(2_000)

// MARK: - Settings rows

/** The Updates section: automatic checks, and "check now" with what it found. */
@Composable
fun UpdatesSection(tick: Int) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = tick
    var auto by remember { mutableStateOf(Prefs.updateChecks(context)) }
    val checkedAt = Prefs.updateCheckedAt(context)
    val ago = if (checkedAt > 0) DateUtils.getRelativeTimeSpanString(checkedAt, System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() } else null
    val detail = when (val s = Updater.state) {
        is State.Checking -> "Checking…"
        is State.Failed -> if (s.release == null) s.message else "Bridge ${s.release.version} didn't install"
        is State.UpToDate -> "Up to date (${Updater.currentVersion(context)})" + (ago?.let { " · checked $it" } ?: "")
        is State.Idle -> ago?.let { "Last checked $it" } ?: "Not checked yet"
        else -> Updater.release()?.let { "Bridge ${it.version} is available" } ?: "Checking…"
    }
    Section("Updates") {
        item {
            SwitchRow(Icons.Rounded.Update, "Check for updates automatically",
                "Once a day, from GitHub. Nothing installs until you tap Update.", auto) {
                auto = it; Prefs.setUpdateChecks(context, it)
            }
        }
        item {
            ActionRow(Icons.Rounded.Refresh, "Check now", detail, onClick = {
                val r = Updater.release()
                if (r != null && Updater.state is State.Available) Updater.start(context)
                else Updater.check(context, manual = true)
            })
        }
    }
}

/**
 * Rows for the Banking and security section: how Bridge's own debugging
 * permission is doing (tap to renew), and the opt-in that stops every
 * computer's permission expiring, behind a confirmation that says what it costs.
 */
fun SectionScope.keyRows(tick: Int) {
    item { KeyStatusRow(tick) }
    item { KeyForeverRow(tick) }
}

@Composable
private fun KeyStatusRow(tick: Int) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = tick
    val now = System.currentTimeMillis()
    val used = Prefs.keyUsedAt(context)
    val expires = KeyKeeper.expiresAt(context)
    val detail = when {
        !KeyKeeper.applies(context) -> "Not set up yet: Set up over USB gets it approved"
        Prefs.keyLost(context) -> "Android stopped trusting it. Set up over USB again."
        KeyKeeper.isForever(context) -> "Never expires (the setting below)"
        used == 0L -> "Not used yet on this version. Bridge checks it on Wi-Fi."
        else -> "Used ${DateUtils.getRelativeTimeSpanString(used, now, DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }}" +
            (expires?.let { e -> if (e > now) " · expires in ${days(e - now)} unless renewed. Bridge renews it on Wi-Fi it knows." else " · may have expired" } ?: "")
    }
    ActionRow(Icons.Rounded.Key, "Bridge's debugging permission", "$detail Tap to check now.", onClick = {
        val started = KeyKeeper.renew(context, reason = "asked on the phone")
        Toast.makeText(context,
            if (!started) "Already checking" else if (KeyKeeper.currentWifi(context) == null) "Bridge needs Wi-Fi for this" else "Checking…",
            Toast.LENGTH_SHORT).show()
    })
}

private fun days(ms: Long): String {
    val d = (ms / 86_400_000L).toInt()
    return when { d < 1 -> "less than a day"; d == 1 -> "1 day"; else -> "$d days" }
}

@Composable
private fun KeyForeverRow(tick: Int) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = tick
    var forever by remember(tick) { mutableStateOf(KeyKeeper.isForever(context)) }
    var confirm by remember { mutableStateOf(false) }
    SwitchRow(
        icon = Icons.Rounded.KeyOff,
        label = "Never let debugging permissions expire",
        detail = if (forever) "On for every computer you've ever allowed, not just Bridge. Turn off to let unused ones lapse again."
                 else "Off: Android forgets unused permissions after a while, and Bridge keeps its own fresh.",
        checked = forever,
        onCheckedChange = { on ->
            if (on) confirm = true
            else KeyKeeper.setForever(context, false)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                ?: run { forever = false }
        },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        icon = { Icon(Icons.Rounded.WarningAmber, null) },
        title = { Text("Keep every permission forever?") },
        text = {
            Text(
                "This is Android's own setting, and it applies to every computer you've ever tapped " +
                    "\"Always allow\" for, not only yours: a work laptop, a friend's PC, a repair shop. Any of " +
                    "them could use USB debugging on this phone again, at any time it's on.\n\n" +
                    "You don't need this for Bridge: it renews its own permission on Wi-Fi it knows. It stays " +
                    "set if you uninstall Bridge, so turn it off here first."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                KeyKeeper.setForever(context, true)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    ?: run { forever = true }
            }) { Text("Keep forever") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
