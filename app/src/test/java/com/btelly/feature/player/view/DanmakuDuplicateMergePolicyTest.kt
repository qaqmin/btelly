package com.btelly.feature.player.view

import com.btelly.feature.player.danmaku.common.DanmakuDuplicateMergePolicy
import com.btelly.model.dm.DmModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DanmakuDuplicateMergePolicyTest {

    @Test
    fun merge_mergesThreeDuplicatesWithCountSuffix() {
        val result = DanmakuDuplicateMergePolicy.merge(
            listOf(
                dm(id = 1, progress = 1_000, content = "抽奖", weight = 1),
                dm(id = 2, progress = 1_600, content = "抽奖", weight = 5),
                dm(id = 3, progress = 2_200, content = "抽奖", weight = 3)
            )
        )

        assertEquals(1, result.size)
        assertEquals("抽奖 ×3", result.first().content)
        assertEquals(5, result.first().weight)
        assertEquals(27, result.first().fontSize)
    }

    @Test
    fun merge_keepsTwoDuplicatesWithoutSuffix() {
        val result = DanmakuDuplicateMergePolicy.merge(
            listOf(
                dm(id = 1, progress = 1_000, content = "来了"),
                dm(id = 2, progress = 1_400, content = "来了")
            )
        )

        assertEquals(1, result.size)
        assertEquals("来了", result.first().content)
    }

    @Test
    fun merge_doesNotMergeAcrossWindow() {
        val result = DanmakuDuplicateMergePolicy.merge(
            listOf(
                dm(id = 1, progress = 1_000, content = "哈哈"),
                dm(id = 2, progress = 4_001, content = "哈哈")
            )
        )

        assertEquals(2, result.size)
    }

    @Test
    fun merge_skipsAdvancedAndScriptDanmaku() {
        val result = DanmakuDuplicateMergePolicy.merge(
            listOf(
                dm(id = 1, progress = 1_000, content = "高级", mode = 7),
                dm(id = 2, progress = 1_100, content = "高级", mode = 7),
                dm(id = 3, progress = 1_200, content = "def text", mode = 9),
                dm(id = 4, progress = 1_300, content = "def text", mode = 9)
            )
        )

        assertEquals(4, result.size)
        assertFalse(result.any { it.content.contains("×") })
    }

    private fun dm(
        id: Long,
        progress: Int,
        content: String,
        mode: Int = 1,
        color: Int = 0xFFFFFF,
        weight: Int = 0
    ): DmModel =
        DmModel(
            id = id,
            progress = progress,
            content = content,
            mode = mode,
            color = color,
            fontSize = 25,
            weight = weight
        )
}
