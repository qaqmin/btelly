package com.btelly.feature.player

import com.btelly.model.proto.DmSmartFilterConfigProto
import com.btelly.model.proto.DmWebViewReplyProto
import com.btelly.model.proto.DmRestrictPeriodProto
import com.btelly.model.proto.DanmuWebPlayerConfigProto
import java.io.Serializable

data class DanmakuFilterContext(
    val smartFilterConfig: DmSmartFilterConfigProto = DmSmartFilterConfigProto(),
    val playerConfig: DanmuWebPlayerConfigProto = DanmuWebPlayerConfigProto(),
    val reportFilters: List<String> = emptyList(),
    val restrictPeriods: List<DmRestrictPeriodProto> = emptyList()
) : Serializable {
    companion object {
        val EMPTY = DanmakuFilterContext()

        fun fromView(view: DmWebViewReplyProto?): DanmakuFilterContext {
            if (view == null) return EMPTY
            return DanmakuFilterContext(
                smartFilterConfig = view.smartFilterConfig,
                playerConfig = view.playerConfig,
                reportFilters = view.reportFilters,
                restrictPeriods = view.restrictPeriods
            )
        }
    }
}
