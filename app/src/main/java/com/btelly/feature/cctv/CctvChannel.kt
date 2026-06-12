package com.btelly.feature.cctv

import java.io.Serializable

data class CctvChannel(
    val number: Int,
    val id: String,
    val title: String,
    val description: String = "央视官方直播",
    val logoUrl: String = ""
) : Serializable
