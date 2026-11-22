# ColorOS Launcher 17.5.5 — 真机验收（8×4 Loader + 天气卡片）

基准 APK：`OplusLauncher-17.5.5.apk`，SHA256 `b487204310bb5c8180ac3092950a258581c4468793541bbcea21f9aa6dd67b56`，二进制 Manifest `versionName=17.5.5`、`versionCode=170050005`。

本分支已将 17.3.9 不存在于 17.5.5 的 `j5.i/p5.c/com.android.launcher.c6/LayoutUtilsManager` Hook 改为新 DEX 中真实存在的 `yn9.v/uh9.a,wjb.b,com.android.launcher.layout.i`，并修正持久化方法 `zv5.o`。**现阶段仅完成静态符号与调用点验证；需要实机确认**。

## 注意：当前布局已有冲突

旧日志 `OplusLoaderCursor` 报过天气卡片 `id=168`，`cell=(4,0)`、`span=4x2`，同时多个图标出现在 `(6,0)`、`(7,0)`、`(6,1)`、`(7,1)`，相互重叠。即使新边界成为 8×4，原生 occupancy 也会拒绝重叠条目。测试时**不要强制覆写任何图标或卡片的 DB 坐标**。

## 新增方向一致性验收

本版本配置文件里的 `8 4` 表示**横屏基础尺寸**，不是禁止旋转的固定工作区。保持至少一页空白测试工作区，分别在竖屏和横屏验证：

| 方向 | 真实工作区 | `effective_loader_bounds` |
| --- | --- | --- |
| 横屏（自然方向或旋转 180°） | **8×4** | **8×4** |
| 竖屏（两种竖屏旋转） | **4×8** | **4×8** |

`native_loader_bounds` 可以仍然是系统原生 6×4 或 4×6；它是未经模块调整的对照值。判断是否真正命中 Hook，应以 `effective_loader_bounds` 和对应加载日志为准。

注意：竖屏时小组件与图标的位置是否正确，还取决于原生 `vCellX/vCellY` 与模型冲突检测，网格转成 4×8 不等于保证所有旧项目可立即恢复显示。**禁止因为项目暂时不显示就清空桌面或再增加覆盖式迁移。**

## 依次验收

1. 备份桌面数据库（主库与 WAL），测试时不使用重要图标。在 LSPosed 中启用本次模块，配置 8×4，清理旧日志状态。
2. 仅重启 Launcher，不清除数据；观察 UI 是否仍 8×4、已有图标是否发生新的自动重排。
3. 模块日志应包含：
   - `native grid source hook installed`
   - `FREE model query installed: public static boolean com.android.launcher.layout.i.c()`
   - `resolved model loader boundary symbol: public final kotlin.Pair yn9.v(kotlin.Pair)`
   - `final model/loader grid bounds hook installed`
   - `FREE loader mode ACTIVE`（Loader 模式实际被查询时）
4. 在设备读取（只读）：
   ```bash
   adb shell su -c 'cat /storage/emulated/0/Android/data/com.android.launcher/files/launcher_grid_status'
   adb shell su -c 'cat /storage/emulated/0/Android/data/com.android.launcher/files/launcher_loader_status'
   ```
   期望 `configured_landscape=8x4`，`actual_workspace=8x4`，`effective_loader_bounds=8x4`。
5. 检查 `OplusLoaderCursor` 是否还出现 `out of screen bounds (6x4)`；如果没有 6x4 报错但出现 `already occupied`，就转入冲突修复阶段，不得继续扩大网格维度来掩盖重复占位。
6. 单独检查卡片 ID 168 是否仍存在于 `singledesktopitems`。如果 DB 行保留却不显示，针对 `processCard() -> checkAndAddItem()` 的原生占位校验搜集日志；卡片类型 100，不是普通 Widget 5。
7. 测试 8×4 下新放置的**测试**图标跨重启是否保留，然后再测试挤压和文件夹/组件绑定。任何已有记录的真实删除都应立即停止继续重启测试。

## 判定

- `effective_loader_bounds` 仍为 6x4 / 文件无内容：Hook 未命中或者诊断程序失效，先检查真实方法名及 `LauncherApplication.q`。
- `effective_loader_bounds=8x4`，仍有 `out of screen bounds (6x4)`：另有独立的边界来源未覆盖，需追踪具体调用点。
- `effective_loader_bounds=8x4`，`already occupied`：需要按原始 DB `_id` 做冲突分组，保留所有记录，选择最小移动方案。
- 卡片 ID 168 仍存在但不可见：Loader 提前拒绝或绑定失败；不能自行补加同名卡片以替代证据。
- ID 168 已不存在：已发生删除，优先恢复备份，然后根据日志追 `commitDeleted`；模块不能凭空恢复已删的卡片。

此协议不包含任何删除、重排或修改真实布局数据库的命令。PR 在上述验收前不得视为稳定修复。
