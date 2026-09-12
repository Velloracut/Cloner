package com.vellora.dualapp.virtual

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

private const val TAG = "VirtualEngine"

/**
 * The ONLY activity the system actually knows about for cloned apps (it's
 * declared in AndroidManifest.xml). Normally VirtualInstrumentation.newActivity()
 * swaps in the REAL target Activity before this class is ever instantiated —
 * reaching this onCreate() means that swap fell through to the fallback
 * (open "View Logs" in the app's main screen to see why). Shows a visible
 * message instead of a silent blank screen so that failure is obvious.
 */
class VirtualStubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.e(TAG, "VirtualStubActivity.onCreate reached — newActivity() fell back, real target didn't load")
        setContentView(TextView(this).apply {
            text = "Cloned app load nahi ho saki.\nApp mein \"View Logs\" khol kar dekhein."
            textSize = 16f
            setPadding(48, 96, 48, 48)
        })
    }
}


