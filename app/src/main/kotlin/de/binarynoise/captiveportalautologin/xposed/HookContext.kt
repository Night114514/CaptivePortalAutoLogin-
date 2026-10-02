package de.binarynoise.captiveportalautologin.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Process-local installation and diagnostics; no framework types escape into app code. */
internal class HookContext(private val api: XposedInterface, private val processName: String) {
    private val installed = mutableSetOf<Method>()

    fun optional(feature: String, loader: ClassLoader, install: () -> Unit) {
        try {
            install()
        } catch (error: ReflectiveOperationException) {
            unavailable(feature, loader, error)
        } catch (error: IllegalArgumentException) {
            unavailable(feature, loader, error)
        } catch (error: SecurityException) {
            unavailable(feature, loader, error)
        } catch (error: LinkageError) {
            unavailable(feature, loader, error)
        }
    }

    private fun unavailable(feature: String, loader: ClassLoader, error: Throwable) {
        api.log(Log.WARN, TAG, "$feature unavailable in $processName; loader=$loader", error)
    }

    @Synchronized
    fun intercept(method: Method, hooker: XposedInterface.Hooker) {
        if (method in installed) return
        api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
        installed.add(method)
        api.log(Log.INFO, TAG, "Installed $method in $processName; loader=${method.declaringClass.classLoader}")
    }

    companion object {
        const val TAG = "CaptivePortalAutoLogin"
    }
}

internal fun ClassLoader.targetClass(name: String): Class<*> = Class.forName(name, false, this)

internal fun Class<*>.targetField(name: String, type: Class<*>) = getDeclaredField(name).apply {
    require(this.type == type) { "$declaringClass.$name has type ${this.type}, expected $type" }
    isAccessible = true
}

internal fun Class<*>.targetMethod(name: String, result: Class<*>, vararg parameters: Class<*>) =
    getDeclaredMethod(name, *parameters).apply {
        require(returnType == result) { "$this returns $returnType, expected $result" }
        isAccessible = true
    }
