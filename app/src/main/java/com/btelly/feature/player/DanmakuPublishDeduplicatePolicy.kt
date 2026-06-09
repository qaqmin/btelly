package com.btelly.feature.player

import com.btelly.model.dm.DmModel
import com.btelly.model.dm.SpecialDanmakuModel

internal fun List<DmModel>.distinctRegularDanmaku(): List<DmModel> {
    if (size < 2) return sortedBy { it.progress }
    val seen = HashSet<String>(size)
    return asSequence()
        .filter { seen.add(it.danmakuIdentityKey()) }
        .sortedBy { it.progress }
        .toList()
}

internal fun List<SpecialDanmakuModel>.distinctSpecialDanmaku(): List<SpecialDanmakuModel> {
    if (size < 2) return sortedBy { it.progress }
    val seen = HashSet<String>(size)
    return asSequence()
        .filter { seen.add(it.specialDanmakuIdentityKey()) }
        .sortedBy { it.progress }
        .toList()
}

internal fun DmModel.danmakuIdentityKey(): String {
    if (id > 0L) return "id:$id"
    val normalizedIdStr = idStr.trim()
    if (normalizedIdStr.isNotEmpty()) return "id:$normalizedIdStr"
    return listOf(
        "fallback",
        progress,
        mode,
        color,
        colorful,
        colorfulSrc.trim(),
        fontSize,
        pool,
        attr,
        midHash.trim(),
        ctime,
        action.trim(),
        animation.trim(),
        content.trim()
    ).joinToString(separator = "|")
}

internal fun SpecialDanmakuModel.specialDanmakuIdentityKey(): String {
    if (id > 0L) return "id:$id"
    return listOf(
        "fallback",
        progress,
        content.trim(),
        color,
        fontSize,
        x,
        y,
        anchorX,
        anchorY,
        alpha,
        bold,
        strokeColor,
        strokeWidth,
        durationMs,
        scaleX,
        scaleY,
        rotation,
        animations
    ).joinToString(separator = "|")
}
