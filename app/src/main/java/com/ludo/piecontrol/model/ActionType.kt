package com.ludo.piecontrol.model

import com.ludo.piecontrol.R

enum class ActionType(val defaultLabel: String, val iconRes: Int) {
    BACK("Back", R.drawable.ic_action_back),
    HOME("Home", R.drawable.ic_action_home),
    RECENTS("Recent Apps", R.drawable.ic_action_recents),
    NOTIFICATIONS("Notifications", R.drawable.ic_action_notifications),
    QUICK_SETTINGS("Quick Settings", R.drawable.ic_action_settings),
    ASSISTANT("Voice Assistant", R.drawable.ic_action_assistant),
    POWER("Power Menu", R.drawable.ic_action_power),
    SCREENSHOT("Screenshot", R.drawable.ic_action_screenshot),
    LOCK_SCREEN("Lock Screen", R.drawable.ic_action_lock),
    VOLUME_UP("Volume +", R.drawable.ic_action_volume_up),
    VOLUME_DOWN("Volume -", R.drawable.ic_action_volume_down),
    FLASHLIGHT("Flashlight", R.drawable.ic_action_flashlight),
    APP("Open App", R.drawable.ic_action_app),
    URL("Open URL", R.drawable.ic_action_web),
    SHORTCUT("Shortcut", R.drawable.ic_action_shortcut);

    companion object {
        fun fromString(name: String): ActionType {
            return try {
                valueOf(name)
            } catch (e: Exception) {
                BACK
            }
        }
    }
}
