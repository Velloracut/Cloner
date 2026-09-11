package com.vellora.dualapp.virtual

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity

private const val TAG = "VirtualEngine"

/**
 * The ONLY activity the system actually knows about for cloned apps (it's
 * declared in AndroidManifest.xml). Normally VirtualInstrumentation.newActivity()
 * swaps in the REAL target Activity before this class is ever instantiated —
 * reaching this onCreate() means that swap fell through to the fallback
 * (check logcat tag "VirtualEngine" for why). Shows a visible message
 * instead of a silent blank screen so that failure is obvious.
 */
class VirtualStubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "VirtualStubActivity.onCreate reached — newActivity() fell back, real target didn't load")
        setContentView(TextView(this).apply {
            text = "Cloned app load nahi ho saki.\nLogcat tag \"VirtualEngine\" check karein."
            textSize = 16f
            setPadding(48, 96, 48, 48)
        })
    }
}

