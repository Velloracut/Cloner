package com.vellora.dualapp.virtual

import java.lang.reflect.Method

/**
 * Android 9 (P) se upar, framework ke "hidden"/internal fields aur methods
 * tak seedha reflection access block hota hai (hidden-api-enforcement).
 * HookManager ko ActivityThread.mInstrumentation jaisi internal fields tak
 * pahunchna zaroori hai, isliye pehle yeh restriction hatani parti hai.
 *
 * Technique (well-known "meta-reflection" trick): khud `Class.getDeclaredMethod`
 * ko reflection ke zariye access karke, uske through `VMRuntime.setHiddenApiExemptions`
 * ko call karna — is raaste se guzarne wale calls enforcement se exempt ho
 * jaate hain. Yeh trick zyadatar Android 9–14 devices par kaam karti hai;
 * kuch OEM builds isay patch kar dete hain, us surat mein hooking fail ho
 * kar launch() gracefully false return karega.
 */
object HiddenApiBypass {
    private var exempted = false

    fun exemptAll() {
        if (exempted) return
        try {
            val metaMethod: Method = Class::class.java
                .getDeclaredMethod("getDeclaredMethod", String::class.java, Array<Class<*>>::class.java)

            val forName = metaMethod.invoke(
                Class::class.java, "forName", arrayOf(String::class.java)
            ) as Method
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>

            val getRuntime = metaMethod.invoke(
                vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)
            ) as Method
            val setHiddenApiExemptions = metaMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)
            ) as Method

            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L"))
            exempted = true
        } catch (e: Throwable) {
            // Bypass is device/version-specific — safe to continue even if
            // it fails; downstream reflection calls will simply throw and
            // HookManager will report itself as not installed.
        }
    }
}
