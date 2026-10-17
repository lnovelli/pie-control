package com.ludo.piecontrol.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PieFolder(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Folder",
    var iconName: String = "ic_action_folder",
    val actions: MutableList<PieAction> = mutableListOf()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("iconName", iconName)
            val actionsArray = JSONArray()
            actions.forEach { actionsArray.put(it.toJson()) }
            put("actions", actionsArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PieFolder {
            val folder = PieFolder(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Folder"),
                iconName = json.optString("iconName", "ic_action_folder")
            )
            val actionsArray = json.optJSONArray("actions")
            if (actionsArray != null) {
                for (i in 0 until actionsArray.length()) {
                    val actObj = actionsArray.optJSONObject(i)
                    if (actObj != null) {
                        folder.actions.add(PieAction.fromJson(actObj))
                    }
                }
            }
            return folder
        }
    }
}
