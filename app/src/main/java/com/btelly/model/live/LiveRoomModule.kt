package com.btelly.model.live

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class LiveRoomModule(
    @SerializedName("id")
    val id: Int = 0,
    @SerializedName("title")
    val title: String = "",
    /** 「查看更多」落地页（area-tags?parentAreaId=X&areaId=Y 或 /all），分区标题 OK 键用。 */
    @SerializedName("link")
    val link: String = ""
) : Serializable
