package com.jhosue.utorrent

data class TorrentUiModel(
    val id: String,
    val title: String,
    val size: String,
    val speed: String,
    val progress: Int,
    val status: String,
    val timeLeft: String,
    val seeds: Int,
    val peers: Int,
    val ratio: String,
    val isPaused: Boolean = false,
    val isComplete: Boolean = false
)
