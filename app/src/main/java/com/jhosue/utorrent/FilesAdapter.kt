package com.jhosue.utorrent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jhosue.utorrent.databinding.ItemFileSelectionBinding

class FilesAdapter(
    private val files: MutableList<TorrentFileItem>,
    private val onSelectionChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<FilesAdapter.FileViewHolder>() {

    class FileViewHolder(val binding: ItemFileSelectionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileSelectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.binding.tvFileName.text = file.name
        holder.binding.tvFileSize.text = android.text.format.Formatter.formatFileSize(holder.itemView.context, file.size)
        holder.binding.cbFile.isChecked = file.isSelected
        
        holder.binding.cbFile.setOnCheckedChangeListener { _, isChecked ->
            file.isSelected = isChecked
            onSelectionChanged(position, isChecked)
        }
    }

    override fun getItemCount() = files.size

    fun updateFiles(newFiles: List<TorrentFileItem>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }
}
