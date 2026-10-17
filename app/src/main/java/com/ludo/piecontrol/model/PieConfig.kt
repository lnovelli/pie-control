package com.ludo.piecontrol.model

import org.json.JSONArray
import org.json.JSONObject

data class PieConfig(
    var enabled: Boolean = true,
    var leftEdgeEnabled: Boolean = true,
    var rightEdgeEnabled: Boolean = true,
    var bottomEdgeEnabled: Boolean = false,
    var triggerHeightDp: Int = 260,
    var triggerWidthDp: Int = 24,
    var triggerPositionPercent: Int = 50,
    var showTriggerIndicator: Boolean = true,
    var triggerIndicatorAlpha: Int = 35,
    var backgroundColorHex: String = "#DD141425",
    var highlightColorHex: String = "#FF00E676",
    var iconColorHex: String = "#FFFFFFFF",
    var pieAlphaPercent: Int = 90,
    var pieScalePercent: Int = 100,
    var hapticFeedback: Boolean = true,
    var layer1: MutableList<PieAction> = mutableListOf(),
    var layer2: MutableList<PieAction> = mutableListOf(),
    var layer3: MutableList<PieAction> = mutableListOf()
) {
    companion object {
        const val MAX_LAYER1 = 5
        const val MAX_LAYER2 = 8
        const val MAX_LAYER3 = 12

        fun createDefault(): PieConfig {
            return PieConfig(
                enabled = true,
                leftEdgeEnabled = true,
                rightEdgeEnabled = true,
                bottomEdgeEnabled = false,
                triggerHeightDp = 260,
                triggerWidthDp = 24,
                triggerPositionPercent = 50,
                showTriggerIndicator = true,
                triggerIndicatorAlpha = 35,
                backgroundColorHex = "#DD141425",
                highlightColorHex = "#FF00E676",
                iconColorHex = "#FFFFFFFF",
                pieAlphaPercent = 90,
                pieScalePercent = 100,
                hapticFeedback = true,
                layer1 = mutableListOf(
                    PieAction(type = ActionType.BACK, label = "Back"),
                    PieAction(type = ActionType.HOME, label = "Home"),
                    PieAction(type = ActionType.RECENTS, label = "Recents"),
                    PieAction(type = ActionType.NOTIFICATIONS, label = "Notifications"),
                    PieAction(type = ActionType.ASSISTANT, label = "Assistant")
                ),
                layer2 = mutableListOf(
                    PieAction(type = ActionType.QUICK_SETTINGS, label = "Quick Settings"),
                    PieAction(type = ActionType.SCREENSHOT, label = "Screenshot"),
                    PieAction(type = ActionType.LOCK_SCREEN, label = "Lock"),
                    PieAction(type = ActionType.POWER, label = "Power"),
                    PieAction(type = ActionType.FLASHLIGHT, label = "Torch"),
                    PieAction(type = ActionType.VOLUME_UP, label = "Vol +"),
                    PieAction(type = ActionType.VOLUME_DOWN, label = "Vol -")
                ),
                layer3 = mutableListOf(
                    PieAction(type = ActionType.URL, label = "Google", data = "https://www.google.com")
                )
            )
        }

        fun fromJson(json: JSONObject): PieConfig {
            val config = PieConfig(
                enabled = json.optBoolean("enabled", true),
                leftEdgeEnabled = json.optBoolean("leftEdgeEnabled", true),
                rightEdgeEnabled = json.optBoolean("rightEdgeEnabled", true),
                bottomEdgeEnabled = json.optBoolean("bottomEdgeEnabled", false),
                triggerHeightDp = json.optInt("triggerHeightDp", 260),
                triggerWidthDp = json.optInt("triggerWidthDp", 24),
                triggerPositionPercent = json.optInt("triggerPositionPercent", 50),
                showTriggerIndicator = json.optBoolean("showTriggerIndicator", true),
                triggerIndicatorAlpha = json.optInt("triggerIndicatorAlpha", 35),
                backgroundColorHex = json.optString("backgroundColorHex", "#DD141425"),
                highlightColorHex = json.optString("highlightColorHex", "#FF00E676"),
                iconColorHex = json.optString("iconColorHex", "#FFFFFFFF"),
                pieAlphaPercent = json.optInt("pieAlphaPercent", 90),
                pieScalePercent = json.optInt("pieScalePercent", 100),
                hapticFeedback = json.optBoolean("hapticFeedback", true)
            )

            fun parseLayer(jsonArray: JSONArray?, max: Int): MutableList<PieAction> {
                val list = mutableListOf<PieAction>()
                if (jsonArray != null) {
                    for (i in 0 until minOf(jsonArray.length(), max)) {
                        val obj = jsonArray.optJSONObject(i)
                        if (obj != null) {
                            list.add(PieAction.fromJson(obj))
                        }
                    }
                }
                return list
            }

            config.layer1 = parseLayer(json.optJSONArray("layer1"), MAX_LAYER1)
            config.layer2 = parseLayer(json.optJSONArray("layer2"), MAX_LAYER2)
            config.layer3 = parseLayer(json.optJSONArray("layer3"), MAX_LAYER3)

            return config
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("enabled", enabled)
            put("leftEdgeEnabled", leftEdgeEnabled)
            put("rightEdgeEnabled", rightEdgeEnabled)
            put("bottomEdgeEnabled", bottomEdgeEnabled)
            put("triggerHeightDp", triggerHeightDp)
            put("triggerWidthDp", triggerWidthDp)
            put("triggerPositionPercent", triggerPositionPercent)
            put("showTriggerIndicator", showTriggerIndicator)
            put("triggerIndicatorAlpha", triggerIndicatorAlpha)
            put("backgroundColorHex", backgroundColorHex)
            put("highlightColorHex", highlightColorHex)
            put("iconColorHex", iconColorHex)
            put("pieAlphaPercent", pieAlphaPercent)
            put("pieScalePercent", pieScalePercent)
            put("hapticFeedback", hapticFeedback)

            val arr1 = JSONArray()
            layer1.take(MAX_LAYER1).forEach { arr1.put(it.toJson()) }
            put("layer1", arr1)

            val arr2 = JSONArray()
            layer2.take(MAX_LAYER2).forEach { arr2.put(it.toJson()) }
            put("layer2", arr2)

            val arr3 = JSONArray()
            layer3.take(MAX_LAYER3).forEach { arr3.put(it.toJson()) }
            put("layer3", arr3)
        }
    }
}
