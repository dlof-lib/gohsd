package com.gohsd.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gohsd.app.databinding.ItemClientBinding

class ClientAdapter(private val onClick: (ClientRow) -> Unit) :
    RecyclerView.Adapter<ClientAdapter.VH>() {

    private var rows: List<ClientRow> = emptyList()

    @Suppress("NotifyDataSetChanged")
    fun submit(r: List<ClientRow>) {
        rows = r
        notifyDataSetChanged()
    }

    class VH(val b: ItemClientBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        val c = h.b.root.context
        h.b.tvName.text = if (r.trusted) r.name else c.getString(R.string.untrusted_fmt, r.name)
        h.b.tvInfo.text = listOfNotNull(r.client.ip, r.client.mac, r.client.signal?.let { "$it dBm" })
            .joinToString(" • ")
        h.b.tvSpeed.text = c.getString(R.string.speed_fmt, Format.speed(r.down), Format.speed(r.up))
        h.b.root.setOnClickListener { onClick(r) }
    }

    override fun getItemCount() = rows.size
}
