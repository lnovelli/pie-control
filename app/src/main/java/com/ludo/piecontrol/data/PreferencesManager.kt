package com.ludo.piecontrol.data

import android.content.Context
import android.content.SharedPreferences
import com.ludo.piecontrol.model.PieConfig
import org.json.JSONObject

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "pie_control_preferences"
        private const val KEY_CONFIG_JSON = "pie_config_json"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun loadConfig(): PieConfig {
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
        return if (!jsonStr.isNullOrEmpty()) {
            try {
                PieConfig.fromJson(JSONObject(jsonStr))
            } catch (e: Exception) {
                PieConfig.createDefault()
            }
        } else {
            val default = PieConfig.createDefault()
            saveConfig(default)
            default
        }
    }

    fun saveConfig(config: PieConfig) {
        val jsonStr = config.toJson().toString()
        prefs.edit().putString(KEY_CONFIG_JSON, jsonStr).apply()
    }

    fun exportConfigJson(): String {
        return loadConfig().toJson().toString(2)
    }

    fun importConfigJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val config = PieConfig.fromJson(json)
            saveConfig(config)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun resetToDefaults(): PieConfig {
        val default = PieConfig.createDefault()
        saveConfig(default)
        return default
    }
}
