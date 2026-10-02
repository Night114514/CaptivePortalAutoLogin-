package de.binarynoise.captiveportalautologin.xposed

import android.os.Build
import android.util.Log
import de.binarynoise.captiveportalautologin.BuildConfig
import de.binarynoise.logger.PlatformImpl
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class CaptivePortalModule : XposedModule() {
    private lateinit var hooks: HookContext

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        hooks = HookContext(this, param.processName)
        if (BuildConfig.DEBUG) {
            PlatformImpl.externalLogSink = { message, error ->
                log(if (error == null) Log.DEBUG else Log.ERROR, HookContext.TAG, message, error)
            }
        }
        log(Log.INFO, HookContext.TAG, "API 102 module loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val loader = param.classLoader
        when (param.packageName) {
            "com.android.systemui" -> {
                if (Build.VERSION.SDK_INT >= 33) HideForegroundServiceHook.install(hooks, loader)
                if (Build.VERSION.SDK_INT >= 31) LocationIndicatorHook.install(hooks, loader)
                if (Build.VERSION.SDK_INT >= 35) DoNotAutoOpenCaptivePortalHook.install(hooks, loader)
            }
            "com.android.settings" -> {
                if (Build.VERSION.SDK_INT >= 30) DoNotAutoOpenCaptivePortalHook.install(hooks, loader)
            }
            "com.android.networkstack", "com.google.android.networkstack" -> {
                if (Build.VERSION.SDK_INT >= 30) NetworkConnectedHook.install(hooks, loader)
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (Build.VERSION.SDK_INT >= 31) ReEnableSupportedNetworksHook.observeWifiService(hooks, param.classLoader)
    }
}
