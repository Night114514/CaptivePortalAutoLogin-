package com.android.systemui.appops

class AppOpItem(@JvmField val mPackageName: String)

class AppOpsControllerImpl {
    @Suppress("UNUSED_PARAMETER")
    fun getActiveAppOps(includeDisabled: Boolean): List<AppOpItem> = emptyList()
}
