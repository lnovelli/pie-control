package com.ludo.piecontrol.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ludo.piecontrol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Focusable only while the user is actively searching. */
class AppSearchPopup(private val context: Context, private val windowManager: WindowManager) {
    private data class App(val label: String, val packageName: String)
    private var root: FrameLayout? = null
    private var scope: CoroutineScope? = null

    fun show() {
        dismiss()
        val themed = ContextThemeWrapper(context, R.style.Theme_PieControl)
        val window = object : FrameLayout(themed) {
            private var requestedKeyboard = false
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus && !requestedKeyboard) {
                    requestedKeyboard = true
                    val search = findViewById<EditText>(R.id.searchInput)
                    search.requestFocus()
                    search.post {
                        if (root === this) {
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val content = LayoutInflater.from(themed).inflate(R.layout.view_app_search_popup, window, false)
        window.addView(content)
        val input = content.findViewById<EditText>(R.id.searchInput)
        val empty = content.findViewById<TextView>(R.id.searchEmpty)
        val results = content.findViewById<RecyclerView>(R.id.searchResults)
        val popupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val adapter = SearchAdapter(themed, popupScope) { app ->
            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launchIntent == null) {
                Toast.makeText(context, "App is no longer available", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    dismiss()
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open app", Toast.LENGTH_SHORT).show()
                }
            }
        }
        results.layoutManager = LinearLayoutManager(themed)
        results.adapter = adapter
        var apps: List<App>? = null
        fun filter() {
            val query = input.text.toString().trim()
            val filtered = apps?.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }.orEmpty()
            adapter.update(filtered)
            empty.visibility = if (apps != null && filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.findViewById<View>(R.id.searchClose).setOnClickListener { dismiss() }
        content.findViewById<View>(R.id.searchScrim).setOnClickListener { dismiss() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        try {
            windowManager.addView(window, params)
            root = window
            scope = popupScope
        } catch (_: Exception) {
            popupScope.cancel()
            Toast.makeText(context, "Unable to open app search", Toast.LENGTH_SHORT).show()
            return
        }
        input.requestFocus()
        popupScope.launch {
            apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .distinctBy { it.activityInfo.packageName }
                    .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .sortedBy { it.label.lowercase() }
            }
            filter()
        }
    }

    fun dismiss() {
        scope?.cancel()
        scope = null
        root?.let { window ->
            root = null
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.windowToken, 0)
            try { windowManager.removeView(window) } catch (_: Exception) { }
        }
    }

    private class SearchAdapter(
        private val context: Context,
        private val scope: CoroutineScope,
        private val onClick: (App) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.Holder>() {
        private var items: List<App> = emptyList()
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivAppIcon)
            val label: TextView = view.findViewById(R.id.tvAppName)
            var job: Job? = null
        }
        fun update(items: List<App>) {
            this.items = items
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(context).inflate(R.layout.item_app_picker, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.job?.cancel()
            holder.label.text = app.label
            holder.icon.setImageDrawable(null)
            holder.itemView.setOnClickListener { onClick(app) }
            holder.job = scope.launch {
                val icon = withContext(Dispatchers.IO) {
                    try { context.packageManager.getApplicationIcon(app.packageName) }
                    catch (_: PackageManager.NameNotFoundException) { null }
                }
                holder.icon.setImageDrawable(icon)
            }
        }
        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            super.onViewRecycled(holder)
        }
    }
}
