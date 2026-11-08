package com.wusper.x86

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ListEntry(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {
    private val items = mutableListOf<ListEntry>()

    fun submit(list: List<ListEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.itemView.setOnClickListener { item.onClick() }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }
}
