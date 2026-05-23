package com.btelly.core.common.perf

import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.btelly.core.common.log.AppLog
import java.util.WeakHashMap

object FirstFrameImageGate {
    private const val TAG = "ImageLoader"

    private data class Gate(
        val label: String,
        val armedAtMs: Long,
        val pending: MutableList<() -> Unit> = ArrayList()
    )

    private val gates = WeakHashMap<RecyclerView, Gate>()

    @Synchronized
    fun arm(recyclerView: RecyclerView, label: String) {
        val previous = gates.remove(recyclerView)
        if (previous != null) {
            AppLog.i(
                TAG,
                "first_frame_gate replaced label=${previous.label} pending=${previous.pending.size}"
            )
            val pending = previous.pending.toList()
            recyclerView.post {
                pending.forEach { it.invoke() }
            }
        }
        gates[recyclerView] = Gate(label = label, armedAtMs = SystemClock.elapsedRealtime())
        AppLog.i(TAG, "first_frame_gate armed label=$label")
    }

    fun defer(imageView: ImageView, url: String, startLoad: () -> Unit): Boolean {
        val gateOwner = findArmedRecyclerView(imageView) ?: return false
        synchronized(this) {
            val gate = gates[gateOwner] ?: return false
            gate.pending += startLoad
            AppLog.i(
                TAG,
                "cover gated until_page_first_draw pending=${gate.pending.size} label=${gate.label} url=${url.takeLast(50)}"
            )
            return true
        }
    }

    fun release(recyclerView: RecyclerView, reason: String) {
        val gate = synchronized(this) { gates.remove(recyclerView) } ?: return
        val pending = gate.pending.toList()
        val waitMs = SystemClock.elapsedRealtime() - gate.armedAtMs
        AppLog.i(
            TAG,
            "first_frame_gate release reason=$reason label=${gate.label} wait=${waitMs}ms pending=${pending.size}"
        )
        recyclerView.post {
            pending.forEach { it.invoke() }
        }
    }

    fun cancel(recyclerView: RecyclerView, reason: String) {
        val gate = synchronized(this) { gates.remove(recyclerView) } ?: return
        AppLog.i(
            TAG,
            "first_frame_gate cancel reason=$reason label=${gate.label} pending=${gate.pending.size}"
        )
    }

    private fun findArmedRecyclerView(view: View): RecyclerView? {
        var current: View? = view
        while (current != null) {
            if (current is RecyclerView && isArmed(current)) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    @Synchronized
    private fun isArmed(recyclerView: RecyclerView): Boolean = gates.containsKey(recyclerView)
}
