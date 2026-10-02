package de.binarynoise.captiveportalautologin.xposed

import de.binarynoise.captiveportalautologin.BuildConfig

internal object HideForegroundServiceHook {
    fun install(hooks: HookContext, loader: ClassLoader) {
        hooks.optional("FgsManagerControllerImpl.UserPackage.updateUiControl()", loader) {
            val control = loader.targetClass("com.android.systemui.qs.FgsManagerControllerImpl\$UIControl")
            val hideEntry = control.targetField("HIDE_ENTRY", control).get(null)
            val userPackage = loader.targetClass("com.android.systemui.qs.FgsManagerControllerImpl\$UserPackage")
            val packageName = userPackage.targetField("packageName", String::class.java)
            val uiControl = userPackage.targetField("uiControl", control)
            hooks.intercept(userPackage.targetMethod("updateUiControl", Void.TYPE)) { chain ->
                val result = chain.proceed()
                if (packageName.get(chain.thisObject) == BuildConfig.APPLICATION_ID) {
                    uiControl.set(chain.thisObject, hideEntry)
                }
                result
            }
        }
    }
}
