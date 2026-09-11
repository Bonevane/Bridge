package com.bonevane.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The one screen of the app. Built in code (no XML layouts) so that
 * everything is in one readable file.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var ticketView: TextView
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var logView: TextView

    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Mac's "Set up over USB" button opens the app with ACTION_START. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == TunnelService.ACTION_START || Prefs.wantRunning(this)) {
            TunnelService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        TunnelState.addListener(refresh)
        render()
    }

    override fun onPause() {
        TunnelState.removeListener(refresh)
        super.onPause()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        fun gap(height: Int) = column.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))

        column.addView(text("Bridge", 30f).apply { setTypeface(typeface, Typeface.BOLD) })
        statusView = text("", 16f)
        column.addView(statusView)
        gap(16)

        toggleButton = button("Start tunnel") {
            if (TunnelState.running) TunnelService.stop(this) else TunnelService.start(this)
        }
        column.addView(toggleButton)
        gap(20)

        column.addView(text("Ticket", 13f).apply { setTypeface(typeface, Typeface.BOLD) })
        ticketView = text("", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        column.addView(ticketView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Copy") { copyTicket() })
        row.addView(button("Share") { shareTicket() })
        column.addView(row)

        column.addView(
            text(
                "Easiest pairing: plug the phone into your Mac, then click " +
                    "\"Set up over USB\" in the Bridge menu. Do that again after every reboot.",
                13f
            )
        )
        gap(20)

        batteryButton = button("Allow Bridge to run in the background") { askBatteryExemption() }
        column.addView(batteryButton)

        column.addView(CheckBox(this).apply {
            text = "Start automatically after reboot"
            isChecked = Prefs.autostart(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutostart(this@MainActivity, checked) }
        })

        column.addView(button("New identity...") { confirmNewIdentity() })
        gap(20)

        // Milestone 2 experiment: start/stop the shell-uid daemon from the phone.
        val daemonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        daemonRow.addView(button("Start daemon") {
            Thread {
                runCatching { DaemonManager.start(this) }
                    .onFailure { TunnelState.log("Daemon start failed: ${it.message}") }
            }.start()
        })
        daemonRow.addView(button("Stop + adb off") {
            Thread { DaemonManager.stop(this, disableAdb = true) }.start()
        })
        column.addView(daemonRow)
        gap(20)

        column.addView(text("Log", 13f).apply { setTypeface(typeface, Typeface.BOLD) })
        logView = text("", 11f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        column.addView(logView)

        return ScrollView(this).apply {
            addView(column)
            // Android 15+ draws apps edge to edge; keep content clear of the status and nav bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun render() {
        statusView.text = TunnelState.status
        toggleButton.text = if (TunnelState.running) "Stop tunnel" else "Start tunnel"
        ticketView.text = currentTicket() ?: "No ticket yet. Start the tunnel."
        val pm = getSystemService(PowerManager::class.java)
        batteryButton.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        logView.text = TunnelState.logText().takeLast(4000)
    }

    private fun currentTicket(): String? = TunnelState.ticket ?: Prefs.ticket(this)

    private fun copyTicket() {
        val ticket = currentTicket() ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Bridge ticket", ticket))
        Toast.makeText(this, "Ticket copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareTicket() {
        val ticket = currentTicket() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ticket)
        startActivity(Intent.createChooser(send, "Send ticket to your Mac"))
    }

    private fun askBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun confirmNewIdentity() {
        AlertDialog.Builder(this)
            .setTitle("Create a new identity?")
            .setMessage("Your Mac will need the new ticket. Run \"Set up over USB\" again afterwards.")
            .setPositiveButton("Reset") { _, _ ->
                TunnelService.stop(this)
                Prefs.resetIdentity(this)
                TunnelState.ticket = null
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
