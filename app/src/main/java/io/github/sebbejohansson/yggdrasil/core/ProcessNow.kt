package io.github.sebbejohansson.yggdrasil.core

import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import android.os.SystemClock

/** What the app itself takes right now: memory, CPU, network. */
data class ProcessNow(
    /** Proportional set size: the app's fair share of RAM, model included. */
    val pssKb: Long,
    val javaHeapKb: Long,
    val nativeHeapKb: Long,
    /** CPU time used since the app's process started, and how long ago that was. */
    val cpuMs: Long,
    val upMs: Long,
    /** Bytes in and out since the phone started (Android counts per app). */
    val rxBytes: Long,
    val txBytes: Long,
) {
    /** Average share of one core since start, in percent. */
    val cpuPercent: Double get() = if (upMs > 0) cpuMs * 100.0 / upMs else 0.0

    companion object {
        /** Reads it; a few milliseconds. Call off the main thread. */
        fun read(): ProcessNow {
            val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val uid = Process.myUid()
            return ProcessNow(
                pssKb = mem.totalPss.toLong(),
                javaHeapKb = (rt.totalMemory() - rt.freeMemory()) / 1024,
                nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
                cpuMs = Process.getElapsedCpuTime(),
                upMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
                rxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0),
                txBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
            )
        }
    }
}
