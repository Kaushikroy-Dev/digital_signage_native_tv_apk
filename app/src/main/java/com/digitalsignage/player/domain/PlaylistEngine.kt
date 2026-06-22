package com.digitalsignage.player.domain

import com.digitalsignage.player.data.api.models.PlaylistItem

object PlaylistEngine {
    fun nextIndex(
        items: List<PlaylistItem>,
        currentIndex: Int,
        playedKeys: Set<String>
    ): Int? {
        if (items.isEmpty()) return null
        val total = items.size
        var next = (currentIndex + 1) % total
        repeat(total) {
            val item = items[next]
            val shouldPlay = item.playInLoop != false || !playedKeys.contains(item.playbackKey())
            if (shouldPlay) return next
            next = (next + 1) % total
        }
        return if (playedKeys.isNotEmpty()) 0 else null
    }
}
