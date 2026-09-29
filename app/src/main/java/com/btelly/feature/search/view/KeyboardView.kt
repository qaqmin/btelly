package com.btelly.feature.search.view

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import com.btelly.R
import com.btelly.core.common.log.AppLog
import com.btelly.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

    interface KeySelectListener {
        fun onInsertText(text: String)
        fun onDelete()
        fun onClear()
        fun onSearch()
    }

    private data class T9CandidateSet(
        val center: String,
        val top: String? = null,
        val left: String? = null,
        val right: String? = null,
        val bottom: String? = null
    )

    private val qweKeys = arrayOf(
        "A", "B", "C", "D", "E", "F",
        "G", "H", "I", "J", "K", "L",
        "M", "N", "O", "P", "Q", "R",
        "S", "T", "U", "V", "W", "X",
        "Y", "Z", "1", "2", "3", "4",
        "5", "6", "7", "8", "9", "0"
    )

    private val t9Labels = arrayOf(
        "\n0/1", "2\nABC", "3\nDEF",
        "4\nGHI", "5\nJKL", "6\nMNO",
        "7\nPQRS", "8\nTUV", "9\nWXYZ"
    )

    private val t9Candidates = arrayOf(
        T9CandidateSet(center = "0", top = "1"),
        T9CandidateSet(center = "2", top = "B", left = "A", right = "C"),
        T9CandidateSet(center = "3", top = "E", left = "D", right = "F"),
        T9CandidateSet(center = "4", top = "H", left = "G", right = "I"),
        T9CandidateSet(center = "5", top = "K", left = "J", right = "L"),
        T9CandidateSet(center = "6", top = "N", left = "M", right = "O"),
        T9CandidateSet(center = "7", top = "Q", left = "P", right = "R", bottom = "S"),
        T9CandidateSet(center = "8", top = "U", left = "T", right = "V"),
        T9CandidateSet(center = "9", top = "X", left = "W", right = "Y", bottom = "Z")
    )

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()
    private val focusInterpolator = OvershootInterpolator()
    private var keySelectListener: KeySelectListener? = null
    private var dispatchKeyDel = true
    private var squareKey = false
    private var isT9Keyboard = appSettings.getCachedBoolean(KEY_IS_T9_KEYBOARD, true)
    private var keyboardContainer: LinearLayoutCompat? = null
    private var activeOverlay: View? = null
    private var activePrimaryKey: View? = null
    private var activeCandidates: T9CandidateSet? = null
    private var keyboardBuilt = false

    init {
        val startMs = SystemClock.elapsedRealtime()
        orientation = VERTICAL
        clipChildren = false
        AppLog.i(TAG, "KeyboardView init elapsed=${SystemClock.elapsedRealtime() - startMs}ms mode=${if (isT9Keyboard) "T9" else "QWE"}")
    }

    fun setKeySelectListener(listener: KeySelectListener?) {
        keySelectListener = listener
    }

    fun setDispatchKeyDel(enabled: Boolean) {
        dispatchKeyDel = enabled
    }

    fun setSquareKey(enabled: Boolean) {
        squareKey = enabled
        if (keyboardBuilt) {
            renderKeyboard()
        }
    }

    fun requestPrimaryFocus(): Boolean {
        ensureKeyboardBuilt()
        return findFirstFocusableChild(keyboardContainer ?: this)?.requestFocus() == true
    }

    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            dispatchKeyDel &&
            event.keyCode == KeyEvent.KEYCODE_DEL
        ) {
            keySelectListener?.onDelete()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && isT9Keyboard && activeOverlay != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    collapseActiveOverlay()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    activeCandidates?.center?.let(::insertOverlayValue)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    return handleOverlayDirectionalInput(activeCandidates?.top)
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    return handleOverlayDirectionalInput(activeCandidates?.left)
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    return handleOverlayDirectionalInput(activeCandidates?.right)
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    return handleOverlayDirectionalInput(activeCandidates?.bottom)
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun buildKeyboard() {
        if (keyboardBuilt) {
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        removeAllViews()

        val headerView = LayoutInflater.from(context).inflate(R.layout.layout_keyboard_header, this, false)
        addView(headerView)

        keyboardContainer = LinearLayoutCompat(context).apply {
            orientation = VERTICAL
            clipChildren = false
            elevation = 20f
        }
        addView(
            keyboardContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val footerView = LayoutInflater.from(context).inflate(R.layout.layout_keyboard_footer, this, false)
        addView(footerView)

        bindHeaderAndFooterButtons()
        renderKeyboard()
        keyboardBuilt = true
        AppLog.i(TAG, "buildKeyboard elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun ensureKeyboardBuilt() {
        if (!keyboardBuilt) {
            buildKeyboard()
        }
    }

    private fun bindHeaderAndFooterButtons() {
        findViewById<AppCompatTextView>(R.id.button_clear)?.apply {
            setOnClickListener {
                keySelectListener?.onClear()
                requestFocus()
            }
            onFocusChangeListener = createScaleFocusListener(this)
        }
        findViewById<AppCompatTextView>(R.id.button_back)?.apply {
            setOnClickListener {
                keySelectListener?.onDelete()
                requestFocus()
            }
            onFocusChangeListener = createScaleFocusListener(this)
        }
        findViewById<AppCompatTextView>(R.id.button_t9)?.apply {
            setOnClickListener {
                if (!isT9Keyboard) {
                    isT9Keyboard = true
                    appSettings.putBooleanAsync(KEY_IS_T9_KEYBOARD, true)
                    renderKeyboard()
                }
            }
            onFocusChangeListener = createScaleFocusListener(this)
        }
        findViewById<AppCompatTextView>(R.id.button_qwe)?.apply {
            setOnClickListener {
                if (isT9Keyboard) {
                    isT9Keyboard = false
                    appSettings.putBooleanAsync(KEY_IS_T9_KEYBOARD, false)
                    renderKeyboard()
                }
            }
            onFocusChangeListener = createScaleFocusListener(this)
        }
        findViewById<AppCompatTextView>(R.id.button_search)?.apply {
            setOnClickListener { keySelectListener?.onSearch() }
            onFocusChangeListener = createScaleFocusListener(this)
        }
        updateKeyboardToggleState()
    }

    private fun renderKeyboard() {
        val startMs = SystemClock.elapsedRealtime()
        collapseActiveOverlay()
        keyboardContainer?.removeAllViews()
        if (isT9Keyboard) {
            renderT9Keyboard()
        } else {
            renderQweKeyboard()
        }
        updateKeyboardToggleState()
        AppLog.i(TAG, "renderKeyboard mode=${if (isT9Keyboard) "T9" else "QWE"} elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun createRowLP(): LayoutParams {
        return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1f)
    }

    private fun createItemLP(): LayoutParams {
        return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1f)
    }

    private fun renderQweKeyboard() {
        val container = keyboardContainer ?: return
        repeat(6) { rowIndex ->
            val row = LinearLayoutCompat(context).apply {
                orientation = HORIZONTAL
            }
            val rowLp = createRowLP().apply { setMargins(0, 10, 0, 0) }
            repeat(6) { columnIndex ->
                val keyIndex = rowIndex * 6 + columnIndex
                val button = LayoutInflater.from(context)
                    .inflate(R.layout.layout_key_qwe, row, false)
                    .findViewById<TextView>(R.id.button_key_qwe)
                button.text = qweKeys[keyIndex]
                button.contentDescription = qweKeys[keyIndex]
                button.setOnClickListener {
                    keySelectListener?.onInsertText(button.text.toString().trim())
                    button.requestFocus()
                }
                button.onFocusChangeListener = createScaleFocusListener(button)
                val itemLp = createItemLP().apply { setMargins(5, 0, 5, 0) }
                row.addView(button, itemLp)
            }
            container.addView(row, rowLp)
        }
    }

    private fun renderT9Keyboard() {
        val container = keyboardContainer ?: return
        repeat(3) { rowIndex ->
            val row = LinearLayoutCompat(context).apply {
                orientation = HORIZONTAL
                clipChildren = false
            }
            val rowLp = createRowLP().apply { setMargins(0, 15, 0, 0) }
            repeat(3) { columnIndex ->
                val keyIndex = rowIndex * 3 + columnIndex
                val keyView = LayoutInflater.from(context).inflate(R.layout.layout_key, row, false)
                val primaryButton = keyView.findViewById<TextView>(R.id.button_key)
                val overlay = keyView.findViewById<View>(R.id.view_select)
                primaryButton.text = t9Labels[keyIndex]
                // T9 键的可见文本是 "2\nABC" 这种双行标签，TalkBack 会把换行读成停顿。
                // 换成口语化描述，同时让"这个键能打出哪些字符"可被朗读出来。
                primaryButton.contentDescription = describeT9Key(t9Labels[keyIndex])
                overlay.visibility = View.GONE
                primaryButton.setOnClickListener {
                    showT9Overlay(
                        primaryButton = primaryButton,
                        overlay = overlay,
                        candidates = t9Candidates[keyIndex]
                    )
                }
                primaryButton.onFocusChangeListener = createScaleFocusListener(primaryButton)
                val itemLp = createItemLP().apply { setMargins(10, 0, 10, 0) }
                row.addView(keyView, itemLp)
            }
            container.addView(row, rowLp)
        }
    }

    private fun showT9Overlay(
        primaryButton: View,
        overlay: View,
        candidates: T9CandidateSet
    ) {
        collapseActiveOverlay()
        activePrimaryKey = primaryButton
        activeOverlay = overlay
        activeCandidates = candidates
        primaryButton.visibility = View.GONE
        overlay.visibility = View.VISIBLE

        val centerButton = overlay.findViewById<TextView>(R.id.button_center)
        val topButton = overlay.findViewById<TextView>(R.id.button_top)
        val leftButton = overlay.findViewById<TextView>(R.id.button_left)
        val rightButton = overlay.findViewById<TextView>(R.id.button_right)
        val bottomButton = overlay.findViewById<TextView>(R.id.button_bottom)

        bindOverlayButton(centerButton, candidates.center)
        bindOverlayButton(topButton, candidates.top)
        bindOverlayButton(leftButton, candidates.left)
        bindOverlayButton(rightButton, candidates.right)
        bindOverlayButton(bottomButton, candidates.bottom)

        centerButton?.requestFocus()
    }

    private fun bindOverlayButton(
        button: TextView?,
        value: String?
    ) {
        button ?: return
        if (value.isNullOrBlank()) {
            button.visibility = View.INVISIBLE
            button.text = ""
            button.setOnClickListener(null)
            button.setOnKeyListener(null)
            button.onFocusChangeListener = null
            return
        }

        button.visibility = View.VISIBLE
        button.text = value
        // 展开态里五个候选位都是同一个 overlay 上的按钮，可读文本彼此分不清，
        // 补上方位描述让 TalkBack 能区分"上是 B / 左是 A / 中心是 1"。
        button.contentDescription = value
        button.onFocusChangeListener = createScaleFocusListener(button)
        button.setOnKeyListener(createOverlayKeyListener())
        button.setOnClickListener {
            insertOverlayValue(button.text.toString())
        }
    }

    /**
     * 把 T9 键的双行标签转成可朗读的描述。
     * "2\nABC" → "按键 2 ABC"；"\n0/1" → "按键 0/1"。
     */
    private fun describeT9Key(label: String): String {
        val parts = label.split("\n").filter { it.isNotBlank() }
        if (parts.isEmpty()) return label
        return context.getString(R.string.keyboard_key_description, parts.joinToString(" "))
    }

    private fun createOverlayKeyListener(): View.OnKeyListener {
        return View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || activeOverlay == null) {
                return@OnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    collapseActiveOverlay()
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    activeCandidates?.center?.let(::insertOverlayValue)
                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> handleOverlayDirectionalInput(activeCandidates?.top)
                KeyEvent.KEYCODE_DPAD_LEFT -> handleOverlayDirectionalInput(activeCandidates?.left)
                KeyEvent.KEYCODE_DPAD_RIGHT -> handleOverlayDirectionalInput(activeCandidates?.right)
                KeyEvent.KEYCODE_DPAD_DOWN -> handleOverlayDirectionalInput(activeCandidates?.bottom)
                else -> false
            }
        }
    }

    private fun handleOverlayDirectionalInput(value: String?): Boolean {
        if (!value.isNullOrBlank()) {
            insertOverlayValue(value)
        }
        return true
    }

    private fun insertOverlayValue(value: String) {
        keySelectListener?.onInsertText(value.trim())
        collapseActiveOverlay()
    }

    private fun collapseActiveOverlay() {
        activeOverlay?.visibility = View.GONE
        activePrimaryKey?.let { key ->
            key.visibility = View.VISIBLE
            key.requestFocus()
        }
        activeOverlay = null
        activePrimaryKey = null
        activeCandidates = null
    }

    private fun updateKeyboardToggleState() {
        // 原来写死 R.color.white 作"未选中"色，浅色皮肤（白/粉）下就是白字白底，根本看不见。
        // 改为解析主题令牌 tvTextTertiary（弱化态）/ tvAccent（激活态）。
        val typedValue = android.util.TypedValue()
        val activeColor = if (context.theme.resolveAttribute(R.attr.tvAccent, typedValue, true)) {
            typedValue.data
        } else {
            ContextCompat.getColor(context, R.color.tv_accent)
        }
        val inactiveColor = if (context.theme.resolveAttribute(R.attr.tvTextTertiary, typedValue, true)) {
            typedValue.data
        } else {
            ContextCompat.getColor(context, R.color.tv_text_tertiary)
        }
        findViewById<AppCompatTextView>(R.id.button_t9)?.setTextColor(
            if (isT9Keyboard) activeColor else inactiveColor
        )
        findViewById<AppCompatTextView>(R.id.button_qwe)?.setTextColor(
            if (isT9Keyboard) inactiveColor else activeColor
        )
    }

    private fun createScaleFocusListener(target: View): View.OnFocusChangeListener {
        return View.OnFocusChangeListener { _, hasFocus ->
            target.animate()
                .setInterpolator(if (hasFocus) focusInterpolator else null)
                .scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f)
                .setDuration(600L)
                .start()
        }
    }

    private fun findFirstFocusableChild(view: View): View? {
        if (view !== this && view.visibility == View.VISIBLE && view.isFocusable) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = findFirstFocusableChild(view.getChildAt(index))
                if (child != null) {
                    return child
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "KeyboardView"
        private const val KEY_IS_T9_KEYBOARD = "isT9Keyboard"
    }
}

