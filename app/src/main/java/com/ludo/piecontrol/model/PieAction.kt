package com.ludo.piecontrol.model

import org.json.JSONObject
import java.util.UUID

data class PieAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val label: String = type.defaultLabel,
    val data: String = "" // Package name for APP, URL for URL, intent URI for SHORTCUT
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("label", label)
            put("data", data)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PieAction {
            val typeStr = json.optString("type", "BACK")
            val type = ActionType.fromString(typeStr)
            return PieAction(
                id = json.optString("id", UUID.randomUUID().toString()),
                type = type,
                label = json.optString("label", type.defaultLabel),
                data = json.optString("data", "")
            )
        }
    }
}
