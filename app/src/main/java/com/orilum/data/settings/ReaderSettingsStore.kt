package com.orilum.data.settings

import java.io.File

/**
 * 阅读配置的用户套读写：`filesDir/settings/reader.json`。
 *
 * 职责：
 *  - [load]：读用户套；无文件或解析失败 → 回退 [ReaderSettings.DEFAULT]（不落盘）。
 *  - [save]：将设置持久化为 JSON（原子写：先写临时文件再 rename）。
 *  - [reset]：一键重置 → 删除用户文件，此后 [load] 返回默认套。
 *
 * 线程安全：方法供 IO 线程调用，建议在协程 Dispatchers.IO 内执行。
 */
class ReaderSettingsStore(private val settingsDir: File) {

    private val file = File(settingsDir, FILE_NAME)
    private val tmp = File(settingsDir, "$FILE_NAME.tmp")

    /** 读取当前生效设置（用户套；无则默认套）。 */
    fun load(): ReaderSettings = runCatching {
        if (file.isFile) ReaderSettings.fromJson(file.readText()) else ReaderSettings.DEFAULT
    }.getOrDefault(ReaderSettings.DEFAULT)

    /** 持久化用户套。 */
    fun save(settings: ReaderSettings) {
        settingsDir.mkdirs()
        // 原子写：先写临时文件再替换，避免中断留下半截 JSON
        tmp.writeText(settings.toJson(2))
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // rename 失败（跨存储等罕见场景）回退直接写目标文件
            file.writeText(settings.toJson(2))
            tmp.delete()
        }
    }

    /** 一键重置：删除用户套文件，之后 [load] 回退默认。 */
    fun reset() {
        if (file.exists()) file.delete()
        if (tmp.exists()) tmp.delete()
    }

    private companion object {
        const val FILE_NAME = "reader.json"
    }
}