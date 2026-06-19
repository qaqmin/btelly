package com.btelly.core.ui.layout

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 焦点锁定的 LinearLayoutManager（用于双排频道列表的左排/右排）。
 *
 * 阻止上下方向的焦点溢出到其他 RecyclerView（左排到顶/到底时不跳右排）。
 * 重写 [focusSearch]：垂直方向到边界时返回当前焦点项，不交给系统搜索其他 View。
 */
class FocusLockLinearLayoutManager : LinearLayoutManager {

    constructor(context: Context) : super(context)
    constructor(context: Context, orientation: Int, reverseLayout: Boolean) : super(context, orientation, reverseLayout)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    /**
     * 拦截焦点搜索：垂直方向（UP/DOWN）到边界时，返回当前焦点项锁住。
     * 水平方向（LEFT/RIGHT）不拦截，允许左右切排。
     */
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        // 上下到边界：锁住当前项，不溢出
        if (focusDirection == View.FOCUS_UP || focusDirection == View.FOCUS_DOWN) {
            return focused
        }
        return super.onFocusSearchFailed(focused, focusDirection, recycler, state)
    }
}
