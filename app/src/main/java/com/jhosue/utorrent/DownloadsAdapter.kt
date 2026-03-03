package com.jhosue.utorrent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadsAdapter(
    private val downloads: MutableList<TorrentUiModel>,
    private val onItemClick: (TorrentUiModel) -> Unit,
    private val onItemLongClick: (TorrentUiModel) -> Unit,
    private val onActionClick: (TorrentUiModel) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.DownloadViewHolder>() {

    class DownloadViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_title)
        val speed: TextView = view.findViewById(R.id.tv_speed)
        val timeLeft: TextView = view.findViewById(R.id.tv_time_left)
        val progress: ProgressBar = view.findViewById(R.id.progress_bar)
        val downloaded: TextView = view.findViewById(R.id.tv_downloaded)
        val totalSize: TextView = view.findViewById(R.id.tv_total_size)
        val action: ImageButton = view.findViewById(R.id.btn_action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return DownloadViewHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        val item = downloads[position]
        holder.title.text = item.title
        holder.speed.text = item.speed
        holder.timeLeft.text = item.timeLeft
        holder.progress.progress = item.progress
        holder.totalSize.text = item.size
        holder.downloaded.text = "${item.status}" // Using status as secondary info for now

        if (item.isComplete) {
            holder.action.setImageResource(R.drawable.ic_check_vector)
            holder.action.setOnClickListener(null)
            holder.action.isClickable = false
            holder.action.isFocusable = false
        } else {
            holder.action.isClickable = true
            holder.action.isFocusable = true
            if (item.isPaused) {
                holder.action.setImageResource(R.drawable.ic_play_vector)
            } else {
                holder.action.setImageResource(R.drawable.ic_pause_vector)
            }
            holder.action.setOnClickListener { 
                android.widget.Toast.makeText(it.context, "Sending Pause/Resume signal...", android.widget.Toast.LENGTH_SHORT).show()
                onActionClick(item) 
            }
        }

        val longPressHandler = View.OnLongClickListener {
            onItemLongClick(item)
            true
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.isLongClickable = true
        holder.itemView.setOnLongClickListener(longPressHandler)
        holder.title.isLongClickable = true
        holder.title.setOnLongClickListener(longPressHandler)
        holder.speed.isLongClickable = true
        holder.speed.setOnLongClickListener(longPressHandler)
        holder.timeLeft.isLongClickable = true
        holder.timeLeft.setOnLongClickListener(longPressHandler)
        holder.downloaded.isLongClickable = true
        holder.downloaded.setOnLongClickListener(longPressHandler)
        holder.totalSize.isLongClickable = true
        holder.totalSize.setOnLongClickListener(longPressHandler)
        holder.progress.isLongClickable = true
        holder.progress.setOnLongClickListener(longPressHandler)
    }

    override fun getItemCount() = downloads.size

    fun updateList(newItems: List<TorrentUiModel>) {
        downloads.clear()
        downloads.addAll(newItems)
        notifyDataSetChanged()
    }
}

