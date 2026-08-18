package com.vdcontroller.launcher

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vdcontroller.R

data class AppItem(
    val label: String,
    val packageName: String,
    val info: ApplicationInfo
)

class AppListAdapter(
    private val pm: PackageManager,
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<AppItem>()

    fun submit(list: List<AppItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.icon.setImageDrawable(item.info.loadIcon(pm))
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
    }
}

object AppLoader {
    fun loadLaunchableApps(pm: PackageManager): List<AppItem> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(intent, 0)
        return resolveList.mapNotNull { ri ->
            val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
            AppItem(
                label = ri.loadLabel(pm).toString(),
                packageName = ai.packageName,
                info = ai
            )
        }.sortedBy { it.label.lowercase() }
    }
}
