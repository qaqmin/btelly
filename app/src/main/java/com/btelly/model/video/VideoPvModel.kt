package com.btelly.model.video

import com.google.gson.annotations.SerializedName
import com.btelly.model.video.Owner
import com.btelly.model.video.Stat

data class VideoPvModel(
    @SerializedName("cid")
    val cid: Long = 0,
    
    @SerializedName("page")
    val page: Int = 0,
    
    @SerializedName("from")
    val from: String = "",
    
    @SerializedName("part")
    val part: String = "",
    
    @SerializedName("duration")
    val duration: Long = 0,
    
    @SerializedName("vid")
    val vid: String = "",
    
    @SerializedName("weblink")
    val weblink: String = "",
    
    @SerializedName("dimension")
    val dimension: com.btelly.model.video.Dimension? = null
)
