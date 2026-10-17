package com.ludo.piecontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ludo.piecontrol.R
import com.ludo.piecontrol.model.PieFolder

class FolderAdapter(
    private val folders: MutableList<PieFolder>,
    private val onEditClick: (PieFolder, Int) -> Unit,
    private val onDeleteClick: (PieFolder, Int) -> Unit
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivConfigFolderIcon)
        val name: TextView = view.findViewById(R.id.tvConfigFolderName)
        val count: TextView = view.findViewById(R.id.tvConfigFolderCount)
        val btnEdit: ImageButton = view.findViewById(R.id.btnConfigEditFolder)
        val btnDelete: ImageButton = view.findViewById(R.id.btnConfigDeleteFolder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_config, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = folders.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.name.text = folder.name
        val actionCount = folder.actions.size
        holder.count.text = "$actionCount ${if (actionCount == 1) "action" else "actions"}"

        val iconRes = when (folder.iconName) {
            "ic_folder_apps" -> R.drawable.ic_folder_apps
            "ic_folder_tools" -> R.drawable.ic_folder_tools
            "ic_folder_star" -> R.drawable.ic_folder_star
            else -> R.drawable.ic_action_folder
        }
        holder.icon.setImageResource(iconRes)

        holder.itemView.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onEditClick(folders[pos], pos)
            }
        }

        holder.btnEdit.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onEditClick(folders[pos], pos)
            }
        }

        holder.btnDelete.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onDeleteClick(folders[pos], pos)
            }
        }
    }
}
