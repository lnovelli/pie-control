package com.ludo.piecontrol.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ludo.piecontrol.R
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction

class FolderActionAdapter(
    private val context: Context,
    private val actions: List<PieAction>,
    private val onActionClick: (PieAction) -> Unit
) : RecyclerView.Adapter<FolderActionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivFolderItemIcon)
        val label: TextView = view.findViewById(R.id.tvFolderItemLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_action, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = actions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]
        holder.label.text = action.label

        fun getActionDrawable(act: PieAction): Drawable? {
            if (act.type == ActionType.APP && act.data.isNotEmpty()) {
                try {
                    return context.packageManager.getApplicationIcon(act.data)
                } catch (e: Exception) {}
            }
            if (act.type == ActionType.SHORTCUT && act.data.isNotEmpty()) {
                try {
                    val parsed = Intent.parseUri(act.data, Intent.URI_INTENT_SCHEME)
                    val pkg = parsed.`package` ?: parsed.component?.packageName
                        ?: context.packageManager.resolveActivity(parsed, 0)?.activityInfo?.packageName
                    if (pkg != null) {
                        return context.packageManager.getApplicationIcon(pkg)
                    }
                } catch (e: Exception) {}
            }
            return null
        }

        val customDrawable = getActionDrawable(action)
        if (customDrawable != null) {
            holder.icon.setImageDrawable(customDrawable)
            holder.icon.clearColorFilter()
        } else {
            holder.icon.setImageResource(action.type.iconRes)
            holder.icon.setColorFilter(Color.WHITE)
        }

        holder.itemView.setOnClickListener {
            onActionClick(action)
        }
    }
}
