package com.btelly.network.response

import com.google.gson.annotations.SerializedName
import com.btelly.model.interaction.InteractionInfo
import com.btelly.model.subtitle.SubtitleWrapper
import java.io.Serializable

data class PlayerInfoDataWrapper(
    @SerializedName("interaction")
    val interaction: InteractionInfo? = null,
    @SerializedName("subtitle")
    val subtitle: SubtitleWrapper? = null
) : Serializable
