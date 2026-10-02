package de.binarynoise.captiveportalautologin.xposed

import de.binarynoise.captiveportalautologin.BuildConfig

internal object LocationIndicatorHook {
    fun install(hooks: HookContext, loader: ClassLoader) {
        hooks.optional("AppOpsControllerImpl.getActiveAppOps(boolean)", loader) {
            val controller = loader.targetClass("com.android.systemui.appops.AppOpsControllerImpl")
            val item = loader.targetClass("com.android.systemui.appops.AppOpItem")
            val packageName = item.targetField("mPackageName", String::class.java)
            val method = controller.targetMethod("getActiveAppOps", List::class.java, Boolean::class.javaPrimitiveType!!)
            hooks.intercept(method) { chain ->
                val result = chain.proceed() as List<*>
                result.filter { packageName.get(it) != BuildConfig.APPLICATION_ID }
            }
        }
    }
}
