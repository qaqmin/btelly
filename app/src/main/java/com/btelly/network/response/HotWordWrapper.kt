package com.btelly.network.response

import com.google.gson.annotations.SerializedName
import com.btelly.model.search.HotWordModel
import java.io.Serializable

data class HotWordWrapper(
    @SerializedName("list")
    val list: List<HotWordModel> = emptyList(),
    @SerializedName("message")
    val message: String = ""
) : Serializable
