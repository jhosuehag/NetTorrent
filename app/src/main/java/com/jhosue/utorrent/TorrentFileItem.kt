package com.jhosue.utorrent

data class TorrentFileItem(
    val index: Int,
    val name: String,
    val size: Long,
    var isSelected: Boolean = true
)
