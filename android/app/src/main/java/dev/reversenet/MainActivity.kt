package dev.reversenet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal control screen: start/stop the relay, see its status, toggle the packet dump. */
class MainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 16f }
        val start = Button(this).apply {
            text = "Start relay"
            setOnClickListener { startRelay() }
        }
        val stop = Button(this).apply {
            text = "Stop relay"
            setOnClickListener {
                startService(Intent(this@MainActivity, RelayService::class.java).setAction(RelayService.ACTION_STOP))
            }
        }
        val debug = CheckBox(this).apply {
            text = "Debug packet dump (logcat, tag ReverseNet)"
            isChecked = RelayState.debugDump
            setOnCheckedChangeListener { _, checked -> RelayState.debugDump = checked }
        }
        listOf(statusView, start, stop, debug).forEach(root::addView)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        RelayState.listener = { runOnUiThread { statusView.text = RelayState.status } }
        statusView.text = RelayState.status
    }

    override fun onPause() {
        RelayState.listener = null
        super.onPause()
    }

    private fun startRelay() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, RelayService::class.java))
    }
}
