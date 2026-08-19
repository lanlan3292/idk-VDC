package com.vdcontroller.launcher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
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

    private val allItems = mutableListOf<AppItem>()
    private val items = mutableListOf<AppItem>()

    fun submit(list: List<AppItem>) {
        allItems.clear()
        allItems.addAll(list)
        filter("")
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        items.clear()
        if (q.isEmpty()) {
            items.addAll(allItems)
        } else {
            for (item in allItems) {
                if (item.label.lowercase().contains(q) || item.packageName.lowercase().contains(q)) {
                    items.add(item)
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.pkg.text = item.packageName
        holder.icon.setImageDrawable(item.info.loadIcon(pm))
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val pkg: TextView = v.findViewById(R.id.appPackage)
    }
}

object AppLoader {
    fun loadLaunchableApps(pm: PackageManager): List<AppItem> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        val resolveList = try {
            pm.queryIntentActivities(intent, flags)
        } catch (_: Exception) {
            pm.queryIntentActivities(intent, 0)
        }
        val byPkg = LinkedHashMap<String, AppItem>()
        for (ri in resolveList) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (pkg in byPkg) continue
            byPkg[pkg] = AppItem(
                label = ri.loadLabel(pm).toString(),
                packageName = pkg,
                info = ai
            )
        }
        return byPkg.values.sortedBy { it.label.lowercase() }
    }
}
