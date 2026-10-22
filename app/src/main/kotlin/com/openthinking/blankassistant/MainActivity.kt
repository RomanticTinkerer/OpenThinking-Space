package com.openthinking.blankassistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Setup screen: shows whether OpenThinking Space is the default assistant and has microphone access,
 * and links to the places where each is granted. It is also the assistant's "settings" page.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var micButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        status = TextView(this).apply { textSize = 16f }

        val assistantButton = Button(this).apply {
            text = "Set as default assistant"
            setOnClickListener { startActivity(assistantRoleIntent(this@MainActivity)) }
        }
        micButton = Button(this).apply {
            text = "Allow microphone (voice notes)"
            setOnClickListener { requestMic() }
        }
        val hint = TextView(this).apply {
            textSize = 14f
            text = "In the assistant settings, also switch on \u201cUse screenshot\u201d \u2014 " +
                "that is how the assistant receives the screen when you invoke it."
            setPadding(0, (16 * density).toInt(), 0, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(assistantButton)
            addView(micButton)
            addView(hint)
            // Targeting API 36 forces edge-to-edge, so keep content clear of system bars.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        setContentView(root)

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false) && !hasMic()) requestMic()
    }

    override fun onResume() {
        super.onResume()
        val isDefault = isDefaultAssistant(this)
        val mic = hasMic()
        status.text = buildString {
            append(if (isDefault) "\u2713 Default assistant" else "\u2717 Not the default assistant yet")
            append('\n')
            append(if (mic) "\u2713 Microphone allowed" else "\u2717 Microphone not allowed")
            if (isDefault) append("\n\nHold the power button to capture the screen.")
        }
        micButton.isEnabled = !mic
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) onResume()
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
        private const val REQUEST_MIC = 1
    }
}
