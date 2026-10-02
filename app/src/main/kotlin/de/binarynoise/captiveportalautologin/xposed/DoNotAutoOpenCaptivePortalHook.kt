package de.binarynoise.captiveportalautologin.xposed

internal object DoNotAutoOpenCaptivePortalHook {
    fun install(hooks: HookContext, loader: ClassLoader) {
        hooks.optional("StandardWifiEntry.connect", loader) {
            val entry = loader.targetClass("com.android.wifitrackerlib.StandardWifiEntry")
            val callback = loader.targetClass("com.android.wifitrackerlib.WifiEntry\$ConnectCallback")
            val autoOpen = entry.targetField("mShouldAutoOpenCaptivePortal", Boolean::class.javaPrimitiveType!!)
            // AOSP and the inspected HyperOS overloads delegate to different innermost methods.
            val signatures = listOf(
                listOf(callback),
                listOf(callback, Boolean::class.javaPrimitiveType!!),
                listOf(callback, Boolean::class.javaPrimitiveType!!, String::class.java),
            )
            val methods = entry.declaredMethods.filter {
                it.name == "connect" && it.returnType == Void.TYPE && it.parameterTypes.toList() in signatures
            }
            require(methods.isNotEmpty()) { "No supported StandardWifiEntry.connect signature in ${entry.declaredMethods.toList()}" }
            methods.forEach { method ->
                hooks.intercept(method) { chain ->
                    val result = chain.proceed()
                    autoOpen.setBoolean(chain.thisObject, false)
                    result
                }
            }
        }
    }
}
