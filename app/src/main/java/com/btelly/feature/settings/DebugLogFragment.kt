package com.btelly.feature.settings

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btelly.R
import com.btelly.core.common.log.AppLog
import com.btelly.core.ui.base.BaseFragment
import com.btelly.core.ui.navigation.navigateBackFromUi
import com.btelly.databinding.FragmentDebugLogBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class DebugLogFragment : BaseFragment<FragmentDebugLogBinding>() {

    companion object {
        fun newInstance() = DebugLogFragment()

        private val LEVEL_LABELS = mapOf(
            AppLog.LogEntry.LEVEL_VERBOSE to "V",
            AppLog.LogEntry.LEVEL_DEBUG to "D",
            AppLog.LogEntry.LEVEL_INFO to "I",
            AppLog.LogEntry.LEVEL_WARN to "W",
            AppLog.LogEntry.LEVEL_ERROR to "E"
        )

        private data class FilterOption(val label: String, val level: Int?) {
            companion object {
                val ALL = listOf(
                    FilterOption("ALL", null),
                    FilterOption("D", AppLog.LogEntry.LEVEL_DEBUG),
                    FilterOption("I", AppLog.LogEntry.LEVEL_INFO),
                    FilterOption("W", AppLog.LogEntry.LEVEL_WARN),
                    FilterOption("E", AppLog.LogEntry.LEVEL_ERROR)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var adapter: DebugLogAdapter
    private var currentFilterLevel: Int? = null
    private var lastLogCount = 0
    private var filterButtons: List<AppCompatTextView> = emptyList()
    private var refreshJob: Job? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) exportLogs(uri)
    }

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentDebugLogBinding {
        return FragmentDebugLogBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        binding.buttonBack.setOnClickListener { navigateBackFromUi() }
        binding.buttonExport.setOnClickListener { folderPicker.launch(null) }

        adapter = DebugLogAdapter()
        val layoutManager = object : LinearLayoutManager(requireContext()) {
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                extraLayoutSpace[0] = resources.getDimensionPixelSize(R.dimen.px200)
                extraLayoutSpace[1] = resources.getDimensionPixelSize(R.dimen.px200)
            }
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null

        binding.buttonClear.setOnClickListener {
            AppLog.LogBuffer.clear()
            AppLog.clearCrashLog()
            lastLogCount = 0
            refreshLogs()
        }

        setupFilterBar()
    }

    override fun initData() {
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun setupFilterBar() {
        val container = binding.filterContainer
        container.removeAllViews()
        val buttons = mutableListOf<AppCompatTextView>()

        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px30 = resources.getDimensionPixelSize(R.dimen.px30)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)

        FilterOption.ALL.forEachIndexed { index, option ->
            val btn = AppCompatTextView(requireContext()).apply {
                text = option.label
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(px30, px14, px30, px14)
                isClickable = true
                isFocusable = true
                setBackgroundResource(R.drawable.cell_background)
                setOnClickListener {
                    selectFilter(index, option.level)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.marginStart = px20
            container.addView(btn, lp)
            buttons.add(btn)
        }
        filterButtons = buttons
        selectFilter(0, null)
    }

    private fun selectFilter(selectedIndex: Int, level: Int?) {
        currentFilterLevel = level
        filterButtons.forEachIndexed { index, btn ->
            btn.isSelected = index == selectedIndex
            btn.setTextColor(if (index == selectedIndex) Color.WHITE else Color.GRAY)
        }
        lastLogCount = 0
        refreshLogs()
    }

    private fun refreshLogs() {
        val logs = AppLog.LogBuffer.getLogs()
        val crashLog = AppLog.getCrashLog()
        val filtered = if (currentFilterLevel != null) {
            logs.filter { it.level == currentFilterLevel }
        } else {
            logs
        }
        val displayList = if (crashLog != null) {
            listOf(
                AppLog.LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = AppLog.LogEntry.LEVEL_ERROR,
                    tag = "CRASH",
                    message = crashLog
                )
            ) + filtered
        } else {
            filtered
        }
        val wasAtBottom = !binding.recyclerView.canScrollVertically(1)
        adapter.setData(displayList)
        if (wasAtBottom && displayList.isNotEmpty()) {
            binding.recyclerView.scrollToPosition(displayList.size - 1)
        }
        lastLogCount = logs.size
    }

    private fun startAutoRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (true) {
                delay(500)
                if (!isAdded) return@launch
                val currentSize = AppLog.LogBuffer.getLogs().size
                if (currentSize != lastLogCount) {
                    refreshLogs()
                }
            }
        }
    }

    private fun exportLogs(folderUri: Uri) {
        scope.launch {
            runCatching {
                val fileName = "btelly_log_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(System.currentTimeMillis())
                }.txt"

                withContext(Dispatchers.IO) {
                    val logs = AppLog.LogBuffer.getLogs()
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    val sb = StringBuilder()
                    for (entry in logs) {
                        val level = LEVEL_LABELS[entry.level] ?: "?"
                        val time = timeFormat.format(entry.timestamp)
                        sb.appendLine("$time $level ${entry.tag}: ${entry.message}")
                    }
                    val crashLog = AppLog.getCrashLog()
                    if (crashLog != null) {
                        sb.appendLine()
                        sb.appendLine("========== CRASH LOG ==========")
                        sb.append(crashLog)
                    }

                    val resolver = requireContext().contentResolver
                    resolver.takePersistableUriPermission(
                        folderUri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val fileUri = android.provider.DocumentsContract.createDocument(
                        resolver, folderUri, "text/plain", fileName
                    ) ?: throw IllegalStateException("无法创建文件")
                    resolver.openOutputStream(fileUri)?.use { os ->
                        os.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                    fileName
                }
            }.onSuccess { name ->
                Toast.makeText(requireContext(), "已导出: $name", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}