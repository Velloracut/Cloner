package com.vellora.dualapp.virtual

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The ONLY activity the system actually knows about for cloned apps (it's
 * declared in AndroidManifest.xml). A cloned app's real Activity never gets
 * its own manifest entry — HookManager (Phase 2) will make this stub host
 * the target app's real Activity instance internally, via the hooked
 * Instrumentation, so from the outside it looks like one activity but
 * behaves like whichever cloned app launched it.
 *
 * PHASE 1 (current): empty placeholder, not yet wired to anything.
 */
class VirtualStubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO(Phase 2): HookManager hands this activity the real target
        // Activity instance/classloader to host here.
    }
}
