package com.yu4032.olaunchermod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Device-boundary operations are all off the Compose UI thread. */
internal object RootBridge {
    const val DIR = "/storage/emulated/0/Android/data/com.android.launcher/files"
    const val GRID = "$DIR/launcher_grid"
    const val MARGINS = "$DIR/launcher_workspace_margins"
    private const val INDICATOR = "$DIR/${IndicatorActivityConfig.FILE_NAME}"

    data class Result(val ok: Boolean, val text: String)

    private fun execute(command: String): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            Result(process.waitFor() == 0, output.trim())
        } catch (error: Exception) {
            Result(false, error.message.orEmpty())
        } finally {
            process?.destroy()
        }
    }

    /** Null means Root failed; empty means a missing (valid) config file. */
    suspend fun readChecked(path: String): String? = withContext(Dispatchers.IO) {
        val uid = execute("id -u")
        if (!uid.ok || uid.text != "0") return@withContext null
        val result = execute("if [ -f '$path' ]; then cat '$path'; fi")
        if (result.ok) result.text else null
    }

    suspend fun read(path: String): String = readChecked(path).orEmpty()

    suspend fun readIndicatorActivity(): IndicatorActivityConfig? =
        readChecked(INDICATOR)?.let(IndicatorActivityConfig::parse)

    suspend fun saveIndicatorActivity(config: IndicatorActivityConfig): Boolean =
        withContext(Dispatchers.IO) {
            val content = config.serialize()
            execute("""
                mkdir -p '$DIR' &&
                temp=${'$'}(mktemp '$DIR/.${IndicatorActivityConfig.FILE_NAME}.XXXXXX') &&
                trap 'rm -f "${'$'}temp"' EXIT &&
                printf '%s\n' '$content' > "${'$'}temp" &&
                chmod 644 "${'$'}temp" &&
                mv -f "${'$'}temp" '$INDICATOR' &&
                am force-stop com.android.launcher
            """.trimIndent()).ok
        }

    suspend fun saveGrid(columns: Int, rows: Int, keepEmpty: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            require(columns in 4..8 && rows in 3..8)
            val content = "$columns $rows ${if (keepEmpty) 1 else 0}"
            execute("mkdir -p '$DIR' && printf '%s\\n' '$content' > '$GRID' " +
                    "&& chmod 644 '$GRID' " +
                    "&& rm -f '$DIR/launcher_grid_status' '$DIR/launcher_loader_status' " +
                    "&& am force-stop com.android.launcher").ok
        }

    suspend fun saveMargins(config: WorkspaceMarginConfig): Boolean =
        withContext(Dispatchers.IO) {
            // All characters are validated decimal integers and spaces by parser.
            val content = config.serialize()
            require(content.matches(Regex("[-0-9 ]+")))
            execute("mkdir -p '$DIR' && printf '%s\\n' '$content' > '$MARGINS' " +
                    "&& chmod 644 '$MARGINS' " +
                    "&& rm -f '$DIR/launcher_margin_status' '$DIR/launcher_viewport_status' " +
                    "&& am force-stop com.android.launcher").ok
        }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        val grid = read("$DIR/launcher_grid_status")
        val loader = read("$DIR/launcher_loader_status")
        val margin = read("$DIR/launcher_margin_status")
        val viewport = read("$DIR/launcher_viewport_status")
        buildString {
            appendLine("工作区实际网格")
            appendLine(grid.ifEmpty { "尚无记录，请重启桌面并检查作用域。" })
            appendLine("模型加载边界")
            appendLine(loader.ifEmpty { "尚无记录；检查 yn9.v 的 Hook 日志。" })
            appendLine("工作区边距")
            appendLine(margin.ifEmpty { "默认边距或尚无记录。" })
            appendLine("竖屏可用高度")
            appendLine(viewport.ifEmpty { "尚无竖屏测量记录。" })
        }
    }
}
