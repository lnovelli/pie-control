package com.ludo.piecontrol.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

        val sec = action.secondaryAction
        if (sec != null) {
            binding.ivSecondaryIcon.visibility = View.VISIBLE
            if (sec.type == ActionType.APP && sec.data.isNotEmpty()) {
                try {
                    val appIcon = context.packageManager.getApplicationIcon(sec.data)
                    binding.ivSecondaryIcon.setImageDrawable(appIcon)
                } catch (e: Exception) {
                    binding.ivSecondaryIcon.setImageResource(sec.type.iconRes)
                }
            } else {
                binding.ivSecondaryIcon.setImageResource(sec.type.iconRes)
            }
            binding.tvActionSub.text = "1st: ${action.label} • 2nd: ${sec.label}"
        } else {
            binding.ivSecondaryIcon.visibility = View.GONE
            binding.tvActionSub.text = when (action.type) {
                ActionType.APP -> "App: ${action.data}"
                ActionType.URL -> "Website: ${action.data}"
                ActionType.SHORTCUT -> "Shortcut"
                else -> "Core System Action"
            }
        }

        if (action.type == ActionType.APP && action.data.isNotEmpty()) {
            try {
                val appIcon = context.packageManager.getApplicationIcon(action.data)
                binding.ivActionIcon.setImageDrawable(appIcon)
            } catch (e: Exception) {
                binding.ivActionIcon.setImageResource(action.type.iconRes)
            }
        } else {
            binding.ivActionIcon.setImageResource(action.type.iconRes)
        }

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
