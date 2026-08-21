package com.orilum.util

import android.content.Context
import android.util.Log
import com.orilum.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用内置文件日志。
 *
 * 用法：vivo/OPPO 平板系统层 logcat 有不可关闭的限流（会 `dropped X lines`），
 * 命令行走 `adb shell run-as com.orilum cat files/logs/记录.txt` 可读取（debuggable 包）。
 *
 * - 仅 [BuildConfig.DEBUG] 编译时写入，release 完全跳过（不产生任何开销）。
 * - 落盘到 `<filesDir>/logs/`，按天分文件，大小超 [MAX_BYTES] 后旋转为 `.1`。
 * - 单条 <= [MAX_LINES_BYTES]（Android 内核截断），超长自动截断。
 * - 写盘走单线程 executor，业务线程绝不阻塞。
 */
object FileLogger {

    private const val MAX_LINES_BYTES = 4000
    private const val MAX_BYTES = 1 shl 20 // 单个日志文件 1MB

    /** 应用是否启用文件日志（debug 构建才启用）。 */
    val enabled: Boolean = BuildConfig.DEBUG

    private val dir = AtomicReference<File?>(null)
    private val queue = ConcurrentLinkedQueue<String>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FolioFileLogger").apply { isDaemon = true }
    }

    /** 初始化（Application/Activity onCreate 调用一次即可）。 */
    fun init(context: Context) {
        if (!enabled) return
        if (dir.get() == null) {
            dir.set(File(context.filesDir, "logs").apply { mkdirs() })
        }
    }

    private fun worker() {
        // 后台栈：直接消费整个队列，避免单个写盘慢拖累调用方
        do {
            writeLine(queue.poll() ?: return)
        } while (queue.isNotEmpty())
    }

    private fun writeLine(line: String) {
        val d = dir.get() ?: return
        val name = "sec_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(System.currentTimeMillis())}.txt"
        val f = File(d, name)
        try {
            if (f.length() > MAX_BYTES) {
                val bak = File(d, "$name.1")
                if (bak.exists()) bak.delete()
                f.renameTo(bak)
            }
            FileOutputStream(f, true).use { fos ->
                PrintWriter(fos).use { pw ->
                    pw.println(line)
                    pw.flush()
                }
            }
        } catch (_: Exception) {
            // 落盘失败不影响业务
        }
    }

    private fun enqueue(tag: String, level: Char, msg: String) {
        if (!enabled) return
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(System.currentTimeMillis())
        val m = if (msg.length > MAX_LINES_BYTES) msg.substring(0, MAX_LINES_BYTES) else msg
        queue.offer("$ts $level/$tag: $m")
        executor.execute { worker() }
    }

    /** 批量合并上报（WebView console 高频输出用，避免逐条刷盘）。 */
    fun batched(tag: String, lines: List<String>) {
        if (!enabled) return
        val joined = lines.joinToString(separator = " | ")
        enqueue(tag, 'D', joined)
    }

    fun d(tag: String, msg: String) = enqueue(tag, 'D', msg)
    fun i(tag: String, msg: String) = enqueue(tag, 'I', msg)
    fun w(tag: String, msg: String) = enqueue(tag, 'W', msg)
    fun e(tag: String, msg: String) = enqueue(tag, 'E', msg)
    fun e(tag: String, msg: String, tr: Throwable) =
        enqueue(tag, 'E', "$msg\n${Log.getStackTraceString(tr)}")
}