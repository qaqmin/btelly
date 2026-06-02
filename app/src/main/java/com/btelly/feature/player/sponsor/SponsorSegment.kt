package com.btelly.feature.player.sponsor

data class SponsorSegment(
    val segment: List<Float> = emptyList(),
    val UUID: String = "",
    val category: String = "",
    val actionType: String = "skip",
    val cid: String = "",
    val locked: Int = 0,
    val votes: Int = 0,
    val videoDuration: Float = 0f
) {
    val startTimeMs: Long get() = ((segment.getOrNull(0) ?: 0f) * 1000).toLong()
    val endTimeMs: Long get() = ((segment.getOrNull(1) ?: 0f) * 1000).toLong()
    val isSkipType: Boolean get() = actionType == "skip"

    fun categoryName(): String = when (category) {
        CATEGORY_SPONSOR -> "赞助/恰饭"
        CATEGORY_INTRO -> "过场/开场动画"
        CATEGORY_OUTRO -> "鸣谢/结束画面"
        CATEGORY_SELF_PROMO -> "无偿/自我推广"
        CATEGORY_INTERACTION -> "三连/互动提醒"
        CATEGORY_PREVIEW -> "回顾/概要"
        CATEGORY_MUSIC_OFFTOPIC -> "音乐:非音乐部分"
        CATEGORY_POI_HIGHLIGHT -> "精彩时刻/重点"
        CATEGORY_FILLER -> "离题闲聊/玩笑"
        CATEGORY_PADDING -> "内容填充"
        CATEGORY_EXCLUSIVE_ACCESS -> "付费/专属内容"
        else -> category
    }

    fun categoryColor(): Long = when (category) {
        CATEGORY_SPONSOR -> 0xFF00D400
        CATEGORY_INTRO -> 0xFF00FFFF
        CATEGORY_OUTRO -> 0xFF0202ED
        CATEGORY_SELF_PROMO -> 0xFFFFFF00
        CATEGORY_INTERACTION -> 0xFFCC00FF
        CATEGORY_PREVIEW -> 0xFF008FD6
        CATEGORY_MUSIC_OFFTOPIC -> 0xFFFF9900
        CATEGORY_POI_HIGHLIGHT -> 0xFFFF1684
        CATEGORY_FILLER -> 0xFF7300FF
        CATEGORY_PADDING -> 0xFF222222
        CATEGORY_EXCLUSIVE_ACCESS -> 0xFF008A5C
        else -> 0xFF00D400
    }

    companion object {
        const val CATEGORY_SPONSOR = "sponsor"
        const val CATEGORY_INTRO = "intro"
        const val CATEGORY_OUTRO = "outro"
        const val CATEGORY_SELF_PROMO = "selfpromo"
        const val CATEGORY_INTERACTION = "interaction"
        const val CATEGORY_PREVIEW = "preview"
        const val CATEGORY_MUSIC_OFFTOPIC = "music_offtopic"
        const val CATEGORY_POI_HIGHLIGHT = "poi_highlight"
        const val CATEGORY_FILLER = "filler"
        const val CATEGORY_PADDING = "padding"
        const val CATEGORY_EXCLUSIVE_ACCESS = "exclusive_access"
        val ALL_CATEGORIES = listOf(
            CATEGORY_SPONSOR,
            CATEGORY_INTRO,
            CATEGORY_OUTRO
        )
    }
}
