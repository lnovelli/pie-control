package com.ludo.piecontrol.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ludo.piecontrol.databinding.ItemRingActionBinding
import com.ludo.piecontrol.model.ActionType
import com.ludo.piecontrol.model.PieAction
import java.util.Collections

class RingActionAdapter(
    private val context: Context,
    var actions: MutableList<PieAction>,
    private val onActionClick: (action: PieAction, position: Int) -> Unit,
    private val onActionDelete: (action: PieAction, position: Int) -> Unit,
    private val onOrderChanged: () -> Unit,
    private val onStartDragListener: ((RecyclerView.ViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<RingActionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemRingActionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRingActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]
        val binding = holder.binding

        binding.tvActionLabel.text = action.label

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
            return ContextCompat.getDrawable(context, act.type.iconRes)
        }

        val sec = action.secondaryAction
        if (sec != null) {
            binding.ivSecondaryIcon.visibility = View.VISIBLE
            binding.ivSecondaryIcon.setImageDrawable(getActionDrawable(sec))
            binding.tvActionSub.text = "1st: ${action.label} • 2nd: ${sec.label}"
        } else {
            binding.ivSecondaryIcon.visibility = View.GONE
            binding.tvActionSub.text = when (action.type) {
                ActionType.APP -> "App: ${action.data}"
                ActionType.URL -> "Website: ${action.data}"
                ActionType.FOLDER -> "📁 Folder: ${action.label}"
                ActionType.SHORTCUT -> "Shortcut: ${action.label}"
                else -> "Core System Action"
            }
        }

        binding.ivActionIcon.setImageDrawable(getActionDrawable(action))

        // Tap on action card to reassign / edit
        binding.root.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onActionClick(actions[pos], pos)
            }
        }

        binding.btnDeleteAction.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onActionDelete(actions[pos], pos)
            }
        }

        binding.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDragListener?.invoke(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = actions.size

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(actions, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(actions, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun onDragComplete() {
        onOrderChanged()
    }
}
