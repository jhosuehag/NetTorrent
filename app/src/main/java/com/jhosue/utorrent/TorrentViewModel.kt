package com.jhosue.utorrent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TorrentViewModel : ViewModel() {

    val torrents: StateFlow<List<TorrentUiModel>> = TorrentRepository.torrentListFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloading: StateFlow<List<TorrentUiModel>> = torrents
        .map { list -> list.filter { !it.isComplete } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completed: StateFlow<List<TorrentUiModel>> = torrents
        .map { list -> list.filter { it.isComplete } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pauseTorrent(hash: String) {
        viewModelScope.launch { TorrentRepository.pauseTorrent(hash) }
    }

    fun resumeTorrent(hash: String) {
        viewModelScope.launch { TorrentRepository.resumeTorrent(hash) }
    }

    fun togglePause(hash: String, isPaused: Boolean) {
        TorrentRepository.updateTorrentStateOptimistically(hash, !isPaused)
        if (isPaused) resumeTorrent(hash) else pauseTorrent(hash)
    }
}
