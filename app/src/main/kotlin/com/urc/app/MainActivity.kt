package com.urc.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.urc.core.session.ControlSession
import com.urc.core.session.SessionState
import com.urc.core.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal operator screen (Wi-Fi/TCP only for now): connect + auto-probe + auto-map, one slider per generated
 * binding, explicit ARM, and a STOP button. Always sends neutral when the app is backgrounded.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val values = ConcurrentHashMap<String, Double>()
    private var session: ControlSession? = null
    private var stateJob: Job? = null

    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var armButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val host = EditText(this).apply { hint = "Host (e.g. 192.168.4.1)"; setText("192.168.4.1") }
        val port = EditText(this).apply {
            hint = "Port"; setText("8080"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        val connect = Button(this).apply { text = "Connect & auto-map" }
        armButton = Button(this).apply { text = "ARM"; isEnabled = false }
        val stop = Button(this).apply { text = "STOP" }
        status = TextView(this).apply { text = "Idle" }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(host, port, connect, armButton, stop, status, controls).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        connect.setOnClickListener {
            val p = port.text.toString().toIntOrNull() ?: return@setOnClickListener
            startSession(host.text.toString().trim(), p)
        }
        armButton.setOnClickListener { session?.arm() }
        stop.setOnClickListener { scope.launch { session?.emergencyStop() } }
    }

    private fun startSession(host: String, port: Int) {
        stateJob?.cancel()
        val old = session
        scope.launch { old?.close() }
        val s = ControlSession(TcpTransport(host, port), inputs = { HashMap(values) }, scope = scope)
        session = s
        stateJob = scope.launch {
            s.state.collect { render(s, it) }
        }
        scope.launch { s.connectAndProbe(host) }
    }

    private fun render(s: ControlSession, st: SessionState) {
        armButton.isEnabled = st is SessionState.Ready
        status.text = when (st) {
            SessionState.Idle -> "Idle"
            SessionState.Connecting -> "Connecting..."
            SessionState.Probing -> "Probing (read-only, max 30s)..."
            is SessionState.Ready -> "Ready: ${st.report.protocol} - not sending. Press ARM."
            is SessionState.Armed -> "ARMED - sending"
            is SessionState.Error -> "Error: ${st.message}"
        }
        if (st is SessionState.Ready && controls.childCount == 0) buildControls(s)
        if (st is SessionState.Idle || st is SessionState.Error) controls.removeAllViews()
    }

    private fun buildControls(s: ControlSession) {
        val map = s.controllerMap ?: return
        for (b in map.bindings) {
            controls.addView(TextView(this).apply { text = b.label })
            controls.addView(SeekBar(this).apply {
                max = 200
                progress = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        values[b.id] = (p - 100) / 100.0
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        sb.progress = 100 // springs back to neutral
                    }
                })
            })
        }
    }

    override fun onStop() {
        super.onStop()
        scope.launch { session?.emergencyStop() }
    }

    override fun onDestroy() {
        scope.launch { session?.close() }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }
}
