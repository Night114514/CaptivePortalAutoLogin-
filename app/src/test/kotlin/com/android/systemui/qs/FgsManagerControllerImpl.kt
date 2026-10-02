package com.android.systemui.qs

class FgsManagerControllerImpl {
    enum class UIControl { NORMAL, HIDE_ENTRY }

    class UserPackage(@JvmField val packageName: String) {
        @JvmField var uiControl = UIControl.NORMAL
        fun updateUiControl() {}
    }
}
