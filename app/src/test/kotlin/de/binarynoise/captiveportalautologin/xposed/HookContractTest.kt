package de.binarynoise.captiveportalautologin.xposed

import com.android.networkstack.NetworkStackNotifier
import com.android.systemui.appops.AppOpItem
import com.android.systemui.qs.FgsManagerControllerImpl.UIControl
import com.android.systemui.qs.FgsManagerControllerImpl.UserPackage
import com.android.wifitrackerlib.StandardWifiEntry
import de.binarynoise.captiveportalautologin.BuildConfig
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import android.net.wifi.WifiConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Exercises our interceptors. This does not emulate ART or prove framework installation. */
class HookContractTest {
    private val registered = linkedMapOf<Method, XposedInterface.Hooker>()
    private val modes = mutableListOf<XposedInterface.ExceptionMode>()
    private val diagnostics = mutableListOf<String>()
    private val api = proxy<XposedInterface> { name, args ->
        when (name) {
            "hook" -> {
                val method = args[0] as Method
                lateinit var builder: XposedInterface.HookBuilder
                builder = proxy { operation, values ->
                    when (operation) {
                        "setExceptionMode" -> { modes.add(values[0] as XposedInterface.ExceptionMode); builder }
                        "intercept" -> {
                            registered[method] = values[0] as XposedInterface.Hooker
                            proxy<XposedInterface.HookHandle> { _, _ -> null }
                        }
                        else -> error(operation)
                    }
                }
                builder
            }
            "log" -> { diagnostics.add(args[2] as String); null }
            else -> error(name)
        }
    }
    private val hooks = HookContext(api, "test.process")
    private val loader = requireNotNull(javaClass.classLoader)

    private fun call(
        hooker: XposedInterface.Hooker,
        receiver: Any?,
        arguments: List<Any?> = emptyList(),
        original: () -> Any?,
    ): Any? {
        var calls = 0
        val chain = proxy<XposedInterface.Chain> { name, args ->
            when (name) {
                "getThisObject" -> receiver
                "getArg" -> arguments.getOrNull(args[0] as Int)
                "proceed" -> { calls++; original() }
                else -> error(name)
            }
        }
        try {
            return hooker.intercept(chain)
        } finally {
            assertEquals(1, calls, "Original method must run exactly once")
        }
    }

    @Test
    fun `connect hooks all inspected overloads and clears flag after original`() {
        DoNotAutoOpenCaptivePortalHook.install(hooks, loader)
        assertEquals(listOf(1, 2, 3), registered.keys.map { it.parameterCount }.sorted())
        registered.values.forEach { hooker ->
            val entry = StandardWifiEntry()
            val result = call(hooker, entry) { entry.mShouldAutoOpenCaptivePortal = true; "original" }
            assertEquals("original", result)
            assertFalse(entry.mShouldAutoOpenCaptivePortal)
        }
        assertTrue(modes.all { it == XposedInterface.ExceptionMode.PROTECTIVE })
    }

    @Test
    fun `original exception is propagated without retrying or changing flag`() {
        DoNotAutoOpenCaptivePortalHook.install(hooks, loader)
        val entry = StandardWifiEntry()
        val failure = IllegalStateException("original failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            call(registered.values.first(), entry) { throw failure }
        }
        assertSame(failure, actual)
        assertTrue(entry.mShouldAutoOpenCaptivePortal)
    }

    @Test
    fun `foreground entry changes only this package after original`() {
        HideForegroundServiceHook.install(hooks, loader)
        assertEquals(1, registered.size)
        val hooker = registered.values.single()
        for (name in listOf(BuildConfig.APPLICATION_ID, "some.other.app")) {
            val entry = UserPackage(name)
            call(hooker, entry) { entry.uiControl = UIControl.NORMAL; null }
            assertEquals(if (name == BuildConfig.APPLICATION_ID) UIControl.HIDE_ENTRY else UIControl.NORMAL, entry.uiControl)
        }
    }

    @Test
    fun `appops removes only our items and preserves source list`() {
        LocationIndicatorHook.install(hooks, loader)
        assertEquals(1, registered.size)
        val other = AppOpItem("some.other.app")
        val source = listOf(AppOpItem(BuildConfig.APPLICATION_ID), other, AppOpItem(BuildConfig.APPLICATION_ID))
        assertEquals(listOf(other), call(registered.values.single(), Any()) { source })
        assertEquals(3, source.size)
    }

    @Test
    fun `duplicate installation is ignored and missing targets are diagnosed`() {
        DoNotAutoOpenCaptivePortalHook.install(hooks, loader)
        DoNotAutoOpenCaptivePortalHook.install(hooks, loader)
        assertEquals(3, modes.size)
        val absentLoader = object : ClassLoader(null) {}
        NetworkConnectedHook.install(hooks, absentLoader)
        assertEquals(2, diagnostics.count { "unavailable in test.process" in it })
    }

    @Test
    fun `network notification pending flag is cleared before original and missing state is safe`() {
        NetworkConnectedHook.install(hooks, loader)
        assertEquals(2, registered.size)
        val notifier = NetworkStackNotifier()
        val status = NetworkStackNotifier.TrackedNetworkStatus()
        notifier.mNetworkStatus[null] = status
        val hooker = registered.entries.single { it.key.name == "updateNotifications" }.value
        call(hooker, notifier) { assertFalse(status.mValidatedNotificationPending); null }
        notifier.mNetworkStatus.clear()
        call(hooker, notifier) { null }
    }

    @Test
    fun `venue notification replacement does not invoke original`() {
        NetworkConnectedHook.install(hooks, loader)
        val hooker = registered.entries.single { it.key.name == "isVenueInfoNotificationEnabled" }.value
        val chain = proxy<XposedInterface.Chain> { name, _ -> error("Unexpected chain call: $name") }
        assertEquals(false, hooker.intercept(chain))
    }

    @Test
    fun `wifi target uses service child loader and installs before service construction only once`() {
        val wifiLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("com.android.server.wifi.")) return super.loadClass(name, resolve)
                synchronized(this) {
                    val type = findLoadedClass(name) ?: run {
                        val bytes = loader.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                        defineClass(name, bytes, 0, bytes.size)
                    }
                    if (resolve) resolveClass(type)
                    return type
                }
            }
        }
        ReEnableSupportedNetworksHook.observeWifiService(hooks, loader)
        assertEquals(1, registered.size)
        val start = registered.values.single()
        // A non-Wi-Fi service must not cause any Wi-Fi class lookup or registration.
        call(start, Any(), listOf(String::class.java)) { assertEquals(1, registered.size); null }
        val service = wifiLoader.loadClass("com.android.server.wifi.WifiService")
        repeat(2) {
            call(start, Any(), listOf(service)) {
                val target = registered.keys.single { it.name == "shouldEnableNetwork" }
                assertSame(wifiLoader, target.declaringClass.classLoader)
                assertEquals(2, modes.size)
                null
            }
        }
    }

    @Test
    fun `missing wifi target still starts the service exactly once`() {
        val missingTargetLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.android.server.wifi.WifiBlocklistMonitor") throw ClassNotFoundException(name)
                if (name != "com.android.server.wifi.WifiService") return super.loadClass(name, resolve)
                val bytes = loader.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        ReEnableSupportedNetworksHook.observeWifiService(hooks, loader)
        val service = missingTargetLoader.loadClass("com.android.server.wifi.WifiService")
        call(registered.values.single(), Any(), listOf(service)) { "started" }.also { assertEquals("started", it) }
        assertEquals(1, registered.size)
        assertTrue(diagnostics.any { "shouldEnableNetwork" in it && "unavailable" in it })
    }

    @Suppress("DEPRECATION")
    @Test
    fun `supported SSIDs bypass original but other and null SSIDs preserve original result`() {
        ReEnableSupportedNetworksHook.observeWifiService(hooks, loader)
        call(registered.values.single(), Any(), listOf(loader.loadClass("com.android.server.wifi.WifiService"))) { null }
        val hooker = registered.entries.single { it.key.name == "shouldEnableNetwork" }.value
        // The SDK's JVM stub constructor throws. Allocate this field-only input without
        // executing that stub; no Android behavior is simulated by this allocation.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val config = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, WifiConfiguration::class.java) as WifiConfiguration
        for (ssid in listOf("WIFIonICE", "\"WIFIonICE\"")) {
            config.SSID = ssid
            val chain = proxy<XposedInterface.Chain> { name, _ ->
                if (name == "getArg") config else error("Supported SSID must not proceed")
            }
            assertEquals(true, hooker.intercept(chain))
        }
        for (ssid in listOf("unknown-network-for-test", null)) {
            config.SSID = ssid
            assertEquals(false, call(hooker, Any(), listOf(config)) { false })
            assertEquals(true, call(hooker, Any(), listOf(config)) { true })
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline body: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                body(method.name, args ?: emptyArray())
            } as T
    }
}
