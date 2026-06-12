package com.btelly.feature.cctv

object CctvChannels {
    private val channels = listOf(
        CctvChannel(1, "cctv1", "CCTV-1 综合", logoUrl = logo("cctv1")),
        CctvChannel(2, "cctv2", "CCTV-2 财经", logoUrl = logo("cctv2")),
        CctvChannel(3, "cctv3", "CCTV-3 综艺", logoUrl = logo("cctv3")),
        CctvChannel(4, "cctv4", "CCTV-4 中文国际", logoUrl = logo("cctv4")),
        CctvChannel(5, "cctv5", "CCTV-5 体育", logoUrl = logo("cctv5")),
        CctvChannel(6, "cctv6", "CCTV-6 电影", logoUrl = logo("cctv6")),
        CctvChannel(7, "cctv7", "CCTV-7 国防军事", logoUrl = logo("cctv7")),
        CctvChannel(8, "cctv8", "CCTV-8 电视剧", logoUrl = logo("cctv8")),
        CctvChannel(9, "cctvjilu", "CCTV-9 纪录", logoUrl = logo("cctv9")),
        CctvChannel(10, "cctv10", "CCTV-10 科教", logoUrl = logo("cctv10")),
        CctvChannel(11, "cctv11", "CCTV-11 戏曲", logoUrl = logo("cctv11")),
        CctvChannel(12, "cctv12", "CCTV-12 社会与法", logoUrl = logo("cctv12")),
        CctvChannel(13, "cctv13", "CCTV-13 新闻", logoUrl = logo("cctv13")),
        CctvChannel(14, "cctvchild", "CCTV-14 少儿", logoUrl = logo("cctv14")),
        CctvChannel(15, "cctv15", "CCTV-15 音乐", logoUrl = logo("cctv15")),
        CctvChannel(16, "cctv16", "CCTV-16 奥林匹克", logoUrl = logo("cctv16")),
        CctvChannel(17, "cctv17", "CCTV-17 农业农村", logoUrl = logo("cctv17"))
    )

    fun list(): List<CctvChannel> = channels

    fun defaultIndex(index: Int): Int = index.coerceIn(channels.indices)

    private fun logo(name: String): String {
        val suffix = if (name == "cctvchild") "14" else name.removePrefix("cctv")
        return "$LOGO_BASE_URL/cctv-$suffix$LOGO_SUFFIX"
    }

    private const val LOGO_BASE_URL =
        "https://p1.img.cctvpic.com/photoAlbum/templet/common/DEPA1532314258547503"
    private const val LOGO_SUFFIX = "_180817.png"
}
