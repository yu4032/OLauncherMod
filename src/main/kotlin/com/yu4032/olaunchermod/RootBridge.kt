package com.yu4032.olaunchermod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Device-boundary operations are all off the Compose UI thread. */
internal object RootBridge {
    private const val DE_BASE = "/data/user_de/0/com.android.launcher"
    private const val DE_DIR = ModuleConfigStore.DEFAULT_DE_DIR
    private const val CE_BASE = "/data/user/0/com.android.launcher"
    private const val CE_DIR = ModuleConfigStore.DEFAULT_CE_DIR
    const val DIR = ModuleConfigStore.LEGACY_EXTERNAL_DIR
    const val GRID_FILE = ModuleConfigStore.GRID_FILE
    const val MARGINS_FILE = ModuleConfigStore.MARGINS_FILE
    private const val INDICATOR_FILE = ModuleConfigStore.INDICATOR_FILE

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

    /** Null means Root failed; empty means a missing (valid) file. */
    suspend fun readChecked(path: String): String? = withContext(Dispatchers.IO) {
        val uid = execute("id -u")
        if (!uid.ok || uid.text != "0") return@withContext null
        val result = execute("if [ -f '$path' ]; then cat '$path'; fi")
        if (result.ok) result.text else null
    }

    suspend fun read(path: String): String = readChecked(path).orEmpty()

    /**
     * Reads a module config file from boot-safe internal storage first (DE -> CE),
     * falling back to legacy external storage if not yet migrated.
     */
    suspend fun readConfigChecked(fileName: String): String? = withContext(Dispatchers.IO) {
        require(fileName.matches(Regex("[A-Za-z0-9._-]+")))
        val uid = execute("id -u")
        if (!uid.ok || uid.text != "0") return@withContext null
        val result = execute("""
            for p in '$DE_DIR/$fileName' '$CE_DIR/$fileName' '$DIR/$fileName'; do
                if [ -f "${'$'}p" ]; then
                    cat "${'$'}p"
                    exit 0
                fi
            done
        """.trimIndent())
        if (result.ok) result.text else null
    }

    suspend fun readConfig(fileName: String): String = readConfigChecked(fileName).orEmpty()

    private suspend fun writeConfig(
        fileName: String,
        content: String,
        clearStatusFiles: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        require(fileName.matches(Regex("[A-Za-z0-9._-]+")))
        require(!content.contains('\''))
        val rmStatus = if (clearStatusFiles.isEmpty()) "" else {
            clearStatusFiles.joinToString(" ") { "'$DIR/$it'" }.let { "rm -f $it || true" }
        }
        execute("""
            write_internal() {
                base="${'$'}1"
                dir="${'$'}2"
                target="${'$'}dir/$fileName"
                if [ ! -d "${'$'}base" ]; then return 1; fi
                mkdir -p "${'$'}dir" || return 1
                tmp=${'$'}(mktemp "${'$'}dir/.$fileName.XXXXXX") || return 1
                printf '%s\n' '$content' > "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                chmod 755 "${'$'}dir" 2>/dev/null || true
                chmod 644 "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                owner=${'$'}(stat -c '%u:%g' "${'$'}base" 2>/dev/null || true)
                ctx=${'$'}(stat -c '%C' "${'$'}base" 2>/dev/null || true)
                if [ -n "${'$'}owner" ]; then chown "${'$'}owner" "${'$'}dir" "${'$'}tmp" 2>/dev/null || true; fi
                if [ -n "${'$'}ctx" ] && [ "${'$'}ctx" != "?" ]; then
                    chcon "${'$'}ctx" "${'$'}dir" "${'$'}tmp" 2>/dev/null || restorecon "${'$'}dir" "${'$'}tmp" 2>/dev/null || true
                else
                    restorecon "${'$'}dir" "${'$'}tmp" 2>/dev/null || true
                fi
                mv -f "${'$'}tmp" "${'$'}target" || { rm -f "${'$'}tmp"; return 1; }
                return 0
            }
            write_external() {
                mkdir -p '$DIR' || return 1
                tmp=${'$'}(mktemp '$DIR/.$fileName.XXXXXX') || return 1
                printf '%s\n' '$content' > "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                chmod 644 "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                mv -f "${'$'}tmp" '$DIR/$fileName' || { rm -f "${'$'}tmp"; return 1; }
                return 0
            }
            de_ok=0; ce_ok=0; ext_ok=0
            if write_internal '$DE_BASE' '$DE_DIR'; then de_ok=1; fi
            if write_internal '$CE_BASE' '$CE_DIR'; then ce_ok=1; fi
            if write_external; then ext_ok=1; fi
            if [ -d '$DE_BASE' ] && [ "${'$'}de_ok" -ne 1 ]; then exit 1; fi
            if [ "${'$'}de_ok" -ne 1 ] && [ "${'$'}ce_ok" -ne 1 ] && [ "${'$'}ext_ok" -ne 1 ]; then exit 1; fi
            $rmStatus
            am force-stop com.android.launcher
        """.trimIndent()).ok
    }

    suspend fun readIndicatorActivity(): IndicatorActivityConfig? =
        readConfigChecked(INDICATOR_FILE)?.let(IndicatorActivityConfig::parse)

    suspend fun saveIndicatorActivity(config: IndicatorActivityConfig): Boolean =
        writeConfig(INDICATOR_FILE, config.serialize())

    suspend fun saveGrid(columns: Int, rows: Int, keepEmpty: Boolean): Boolean {
        require(columns in 4..8 && rows in 3..8)
        val content = "$columns $rows ${if (keepEmpty) 1 else 0}"
        return writeConfig(
            GRID_FILE,
            content,
            listOf("launcher_grid_status", "launcher_loader_status")
        )
    }

    suspend fun saveMargins(config: WorkspaceMarginConfig): Boolean {
        // All characters are validated decimal integers and spaces by parser.
        val content = config.serialize()
        require(content.matches(Regex("[-0-9 ]+")))
        return writeConfig(
            MARGINS_FILE,
            content,
            listOf("launcher_margin_status", "launcher_viewport_status")
        )
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
