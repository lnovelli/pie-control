package com.ludo.piecontrol.usage

import android.content.Intent
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import com.ludo.piecontrol.model.PieConfig

/** Packages reachable through ring actions, including secondary actions and nested folders. */
internal fun mappedAppPackages(config: PieConfig): Set<String> {
    val mapped = mutableSetOf<String>()
    val visitedFolders = mutableSetOf<String>()

    fun visit(action: PieAction) {
        when (action.type) {
            ActionType.APP -> action.data.takeIf { it.isNotBlank() }?.let(mapped::add)
            ActionType.SHORTCUT -> if (action.data.isNotBlank()) {
                try {
                    val intent = Intent.parseUri(action.data, Intent.URI_INTENT_SCHEME)
                    (intent.`package` ?: intent.component?.packageName)?.let(mapped::add)
                } catch (_: Exception) {
                    // Invalid shortcut data does not identify a package.
                }
            }
            ActionType.FOLDER -> {
                val folder = config.folders.firstOrNull { it.id == action.data || it.name == action.data }
                if (folder != null && visitedFolders.add(folder.id)) folder.actions.forEach(::visit)
            }
            else -> Unit
        }
        action.secondaryAction?.let(::visit)
    }

    (config.layer1 + config.layer2 + config.layer3).forEach(::visit)
    return mapped
}
