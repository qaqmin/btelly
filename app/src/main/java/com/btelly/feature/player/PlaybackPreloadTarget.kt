package com.btelly.feature.player

import com.btelly.core.model.id.Aid
import com.btelly.core.model.id.Bvid
import com.btelly.core.model.id.Cid
import com.btelly.core.model.id.EpId

data class PlaybackPreloadTarget(
    val aid: Long? = null,
    val bvid: String? = null,
    val cid: Long,
    val epId: Long? = null,
    val source: Source
) {
    enum class Source {
        NEXT_EPISODE,
        PLAY_QUEUE,
        RELATED_VIDEO,
        LIST_TOUCH
    }

    val typedAid: Aid? get() = aid?.let(::Aid)
    val typedBvid: Bvid? get() = bvid?.let(::Bvid)
    val typedCid: Cid get() = Cid(cid)
    val typedEpId: EpId? get() = epId?.let(::EpId)
}
