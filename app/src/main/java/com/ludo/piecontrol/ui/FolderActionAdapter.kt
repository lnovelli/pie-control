package com.ludo.piecontrol.ui

import android.content.Context
import android.graphics.Color
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

        if (action.type == ActionType.APP && action.data.isNotEmpty()) {
            try {
                val appIcon = context.packageManager.getApplicationIcon(action.data)
                holder.icon.setImageDrawable(appIcon)
                holder.icon.clearColorFilter()
            } catch (e: Exception) {
                holder.icon.setImageResource(action.type.iconRes)
                holder.icon.setColorFilter(Color.WHITE)
            }
        } else {
            holder.icon.setImageResource(action.type.iconRes)
            holder.icon.setColorFilter(Color.WHITE)
        }

        holder.itemView.setOnClickListener {
            onActionClick(action)
        }
    }
}
