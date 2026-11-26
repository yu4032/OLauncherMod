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
    const val DOCK_FILE = ModuleConfigStore.DOCK_FILE
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
        clearStatusFiles: List<String> = emptyList(),
        restartLauncher: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        require(fileName.matches(Regex("[A-Za-z0-9._-]+")))
        require(!content.contains('\''))
        val rmStatus = if (clearStatusFiles.isEmpty()) "" else {
            clearStatusFiles.joinToString(" ") { "'$DIR/$it'" }.let { "rm -f $it || true" }
        }
        val restartCmd = if (restartLauncher) "am force-stop com.android.launcher" else ""
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
            $restartCmd
        """.trimIndent()).ok
    }

    suspend fun readIndicatorActivity(): IndicatorActivityConfig? =
        readConfigChecked(INDICATOR_FILE)?.let(IndicatorActivityConfig::parse)

    suspend fun saveIndicatorActivity(config: IndicatorActivityConfig): Boolean =
        writeConfig(INDICATOR_FILE, config.serialize())

    suspend fun readIconFix(): IconReplacementConfig? = withContext(Dispatchers.IO) {
        val uid = execute("id -u")
        if (!uid.ok || uid.text != "0") return@withContext null
        val res = execute("""
            for p in '$DE_DIR/${IconReplacementConfig.RELATIVE_CONF}' '$CE_DIR/${IconReplacementConfig.RELATIVE_CONF}' '$DIR/${IconReplacementConfig.RELATIVE_CONF}' '/data/adb/iconfix.conf'; do
                if [ -f "${'$'}p" ]; then
                    cat "${'$'}p"
                    exit 0
                fi
            done
        """.trimIndent())
        if (!res.ok) return@withContext null
        IconReplacementConfig.parse(res.text)
    }

    suspend fun saveIconFix(config: IconReplacementConfig, restartLauncher: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            val content = config.serialize()
            require(!content.contains('\''))
            val restartCmd = if (restartLauncher) {
                "rm -f '$DE_BASE/databases/app_icons.db'* 2>/dev/null || true\n" +
                    "am force-stop com.android.launcher"
            } else ""
            execute("""
                write_conf() {
                    base="${'$'}1"
                    dir="${'$'}2/iconfix"
                    target="${'$'}dir/iconfix.conf"
                    if [ ! -d "${'$'}base" ]; then return 1; fi
                    mkdir -p "${'$'}dir" "${'$'}dir/icons" || return 1
                    tmp=${'$'}(mktemp "${'$'}dir/.iconfix.conf.XXXXXX") || return 1
                    printf '%s' '$content' > "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                    chmod 755 "${'$'}dir" "${'$'}dir/icons" 2>/dev/null || true
                    chmod 644 "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                    owner=${'$'}(stat -c '%u:%g' "${'$'}base" 2>/dev/null || true)
                    ctx=${'$'}(stat -c '%C' "${'$'}base" 2>/dev/null || true)
                    if [ -n "${'$'}owner" ]; then chown -R "${'$'}owner" "${'$'}dir" 2>/dev/null || true; fi
                    if [ -n "${'$'}ctx" ] && [ "${'$'}ctx" != "?" ]; then
                        chcon -R "${'$'}ctx" "${'$'}dir" 2>/dev/null || restorecon -R "${'$'}dir" 2>/dev/null || true
                    else
                        restorecon -R "${'$'}dir" 2>/dev/null || true
                    fi
                    mv -f "${'$'}tmp" "${'$'}target" || { rm -f "${'$'}tmp"; return 1; }
                    return 0
                }
                write_ext() {
                    dir='$DIR/iconfix'
                    mkdir -p "${'$'}dir" "${'$'}dir/icons" || return 1
                    tmp=${'$'}(mktemp "${'$'}dir/.iconfix.conf.XXXXXX") || return 1
                    printf '%s' '$content' > "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                    chmod 644 "${'$'}tmp" || { rm -f "${'$'}tmp"; return 1; }
                    mv -f "${'$'}tmp" "${'$'}dir/iconfix.conf" || { rm -f "${'$'}tmp"; return 1; }
                    return 0
                }
                de_ok=0; ext_ok=0
                if write_conf '$DE_BASE' '$DE_DIR'; then de_ok=1; fi
                write_conf '$CE_BASE' '$CE_DIR' >/dev/null 2>&1 || true
                if write_ext; then ext_ok=1; fi
                if [ -d '$DE_BASE' ] && [ "${'$'}de_ok" -ne 1 ]; then exit 1; fi
                if [ "${'$'}de_ok" -ne 1 ] && [ "${'$'}ext_ok" -ne 1 ]; then exit 1; fi
                $restartCmd
            """.trimIndent()).ok
        }

    suspend fun installIconImage(srcPath: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            require(fileName.matches(Regex("[A-Za-z0-9._-]+")))
            require(!srcPath.contains('\''))
            execute("""
                copy_one() {
                    base="${'$'}1"
                    icons="${'$'}2/${IconReplacementConfig.RELATIVE_ICONS_DIR}"
                    if [ ! -d "${'$'}base" ]; then return 1; fi
                    mkdir -p "${'$'}icons" || return 1
                    cp -f '$srcPath' "${'$'}icons/$fileName" || return 1
                    chmod 755 "${'$'}2/iconfix" "${'$'}icons" 2>/dev/null || true
                    chmod 644 "${'$'}icons/$fileName" || return 1
                    owner=${'$'}(stat -c '%u:%g' "${'$'}base" 2>/dev/null || true)
                    ctx=${'$'}(stat -c '%C' "${'$'}base" 2>/dev/null || true)
                    if [ -n "${'$'}owner" ]; then chown "${'$'}owner" "${'$'}icons" "${'$'}icons/$fileName" 2>/dev/null || true; fi
                    if [ -n "${'$'}ctx" ] && [ "${'$'}ctx" != "?" ]; then
                        chcon "${'$'}ctx" "${'$'}icons" "${'$'}icons/$fileName" 2>/dev/null || restorecon "${'$'}icons" "${'$'}icons/$fileName" 2>/dev/null || true
                    else
                        restorecon "${'$'}icons" "${'$'}icons/$fileName" 2>/dev/null || true
                    fi
                    return 0
                }
                de_ok=0; ext_ok=0
                if copy_one '$DE_BASE' '$DE_DIR'; then de_ok=1; fi
                copy_one '$CE_BASE' '$CE_DIR' >/dev/null 2>&1 || true
                mkdir -p '$DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}' 2>/dev/null &&
                    cp -f '$srcPath' '$DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$fileName' &&
                    chmod 644 '$DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$fileName' && ext_ok=1 || true
                if [ -d '$DE_BASE' ] && [ "${'$'}de_ok" -ne 1 ]; then exit 1; fi
                if [ "${'$'}de_ok" -ne 1 ] && [ "${'$'}ext_ok" -ne 1 ]; then exit 1; fi
            """.trimIndent()).ok
        }

    suspend fun removeIconImage(fileName: String) {
        if (!fileName.matches(Regex("[A-Za-z0-9._-]+"))) return
        withContext(Dispatchers.IO) {
            execute("""
                rm -f '$DE_DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$fileName' \
                      '$CE_DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$fileName' \
                      '$DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$fileName' 2>/dev/null || true
            """.trimIndent())
        }
    }

    suspend fun clearAllIconFix(): Boolean = withContext(Dispatchers.IO) {
        execute("""
            rm -rf '$DE_DIR/iconfix' '$CE_DIR/iconfix' '$DIR/iconfix' 2>/dev/null || true
            rm -f '$DE_BASE/databases/app_icons.db'* 2>/dev/null || true
            am force-stop com.android.launcher
        """.trimIndent()).ok
    }

    suspend fun refreshLauncherIcons(): Boolean = withContext(Dispatchers.IO) {
        execute("""
            rm -f '$DE_BASE/databases/app_icons.db'* 2>/dev/null || true
            am force-stop com.android.launcher
        """.trimIndent()).ok
    }

    suspend fun exportIconPreview(spec: String, dstPath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (spec.isEmpty() || spec.startsWith("pkg:") || spec.contains("..") || spec.contains('\'')) {
                return@withContext false
            }
            val name = spec.substringAfterLast('/')
            if (!name.matches(Regex("[A-Za-z0-9._-]+"))) return@withContext false
            require(!dstPath.contains('\''))
            execute("""
                for p in '$DE_DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$name' \
                         '$CE_DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$name' \
                         '$DIR/${IconReplacementConfig.RELATIVE_ICONS_DIR}/$name' \
                         '$spec'; do
                    if [ -f "${'$'}p" ]; then
                        cp -f "${'$'}p" '$dstPath' && chmod 644 '$dstPath' && exit 0
                    fi
                done
                exit 1
            """.trimIndent()).ok
        }

    suspend fun saveGrid(columns: Int, rows: Int, keepEmpty: Boolean): Boolean {
        require(columns in 4..8 && rows in 3..8)
        val content = "$columns $rows ${if (keepEmpty) 1 else 0}"
        return writeConfig(
            GRID_FILE,
            content,
            listOf("launcher_grid_status", "launcher_loader_status")
        )
    }

    suspend fun saveMargins(config: WorkspaceMarginConfig): Boolean =
        saveLayoutAdjustments(config, null)

    suspend fun saveLayoutAdjustments(
        margins: WorkspaceMarginConfig,
        dockOffset: DockOffsetConfig?
    ): Boolean {
        val marginContent = margins.serialize()
        require(marginContent.matches(Regex("[-0-9 ]+")))
        if (dockOffset == null) {
            return writeConfig(
                MARGINS_FILE,
                marginContent,
                listOf("launcher_margin_status", "launcher_viewport_status"),
                restartLauncher = true
            )
        }
        val dockContent = dockOffset.serialize()
        require(dockContent.matches(Regex("[-0-9 ]+")))
        val okMargins = writeConfig(
            MARGINS_FILE,
            marginContent,
            listOf("launcher_margin_status", "launcher_viewport_status"),
            restartLauncher = false
        )
        val okDock = writeConfig(
            DOCK_FILE,
            dockContent,
            listOf("launcher_dock_status", "launcher_viewport_status"),
            restartLauncher = true
        )
        return okMargins && okDock
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        val grid = read("$DIR/launcher_grid_status")
        val loader = read("$DIR/launcher_loader_status")
        val margin = read("$DIR/launcher_margin_status")
        val dock = read("$DIR/launcher_dock_status")
        val viewport = read("$DIR/launcher_viewport_status")
        buildString {
            appendLine("工作区实际网格")
            appendLine(grid.ifEmpty { "尚无记录，请重启桌面并检查作用域。" })
            appendLine("模型加载边界")
            appendLine(loader.ifEmpty { "尚无记录；检查 yn9.v 的 Hook 日志。" })
            appendLine("工作区边距")
            appendLine(margin.ifEmpty { "默认边距或尚无记录。" })
            appendLine("Dock 垂直位移")
            appendLine(dock.ifEmpty { "默认位置或尚无记录。" })
            appendLine("竖屏可用高度")
            appendLine(viewport.ifEmpty { "尚无竖屏测量记录。" })
        }
    }
}
