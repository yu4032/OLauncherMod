# OLauncherMod

面向 ColorOS Launcher 的 LSPosed（API 102）桌面网格与自由布局模块。

> 代码中的 ColorOS 内部方法依据 Launcher 17.5.5 的反编译版本检查，尚未经过设备实测；其他版本可能需要适配。

## 功能

- 配置桌面网格的列、行（横屏语义：4–8 列，3–8 行）。
- 在 `OplusInvariantDeviceProfile.injectPreInitGridExt` 初始化之前同步 ColorOS 原生 `layout_cellcount_x/y` 设置，减少重启时恢复默认网格的情况。
- 当变更属于无损扩展（数据库与 Hotseat 配置相同，已有桌面项全部在新范围内）时保留原坐标，不执行会改变排列的网格迁移。缩小网格等情况仍使用原生迁移。
- 可选「保留空位」：拦截 `OplusCellLayout.fillUpEmptyCell` 的自动紧凑排列；不禁用通常的拖拽落点处理。
- **不会在桌面启动时直接更新图标数据库，不调用自动 `reflow`。**
- 在 `OplusCellLayout` 页面初始化后检查真实 `getCountX/getCountY`，并拦截工作区专属的两参数及三参数 `setGridSize` 与旋转用 `setGridCellSize`；仅对真正的工作区页面生效，不改文件夹或 Hotseat。
- 与 ColorOS 的 `c6.b(Context)` 原生网格来源保持一致，避免旋转重新读取旧的 6 列配置。
- 提供「刷新工作区实际网格」状态，核对运行中的网格尺寸，不再依赖设置界面显示的配置推断是否生效。

## 配置

模块设置界面保存到 Launcher 的应用文件目录：

```text
/storage/emulated/0/Android/data/com.android.launcher/files/launcher_grid
```

格式为 `列 行 保留空位`，例如：

```text
8 4 1
```

最后一项：`1` 禁止自动紧凑排列（默认），`0` 保留原生排列。兼容仅有前两项的配置。修改后需重启桌面。

配置范围不超过 8，因为所核对的 ColorOS 17.5.5 版本中 `DeviceGridState` 按单个字符解析行列数，无法正确处理两位数网格。

## 第 7、8 列重启后图标消失的修复

ColorOS 新平板布局的 `OplusLoaderCursor.checkItemPlacement()` 不总是使用 IDP 提供的列数，而是通过 `j5.i.I()` 读取 `p5.c.a(0)` 保存的横屏尺寸。若该静态缓存仍为 6×4，即使实际工作区显示 8×4，加载时也会认为第 7、8 列越界，并将相关桌面项加入 `LoaderCursor.commitDeleted()` 删除列表。

本模块现在同步 `p5.c.a(0)`（横屏）和 `p5.c.a(1)`（竖屏）及其缓存，确保加载阶段采用自定义网格边界。额外记录工作区放置校验失败日志（tag: `OLauncherMod`）。保留原生重叠/越界校验，不拦截所有删除行为。针对坐标仍处于配置网格之内、仅因 `Item position overlap` 被拒绝的桌面项，新增一个数据保护兜底：不把该记录交给 `commitDeleted()` 永久删除。它不会强行显示实际冲突的图标，需根据日志修复冲突。

**注意：此修复阻止后续重启继续发生相同的数据删除，但不会自动恢复之前已从桌面数据库删除的图标记录。** 已删除的快捷方式应从桌面备份恢复，或者重新添加到工作区。更改前请先备份桌面布局。

## 跨版本符号解析

代码已统一采用 **Java/ART 方法名 + 完整函数签名** 解析，不依赖 native 地址、固定字段偏移或 dex 方法序号。对于 `j5.i.I(Pair)`、`p5.c.a(int)` 等混淆方法，如果名称改变，会在已确认的 owner 类内按唯一签名尝试解析；如果有多个候选，则直接拒绝 Hook，防止错误改写桌面布局。

**debug-6 回归修复：**启动预检查仅用于诊断 IDP 与工作区网格入口，不再拥有全局阻止 Hook 的权限。工作区、加载器、旋转和排列模式逐项解析、独立安装；任意单一符号失败只影响对应功能，不再把已经有效的 8×4 工作区整体退回 6×4。**类名整体混淆或方法语义变化无法靠函数名解析自动适配**，必须使用设备实际 APK 核验。详细规则见 [符号解析与兼容性](docs/SYMBOL_RESOLUTION.md)。

符号解析包含 12 项单元测试，其中两项专门防止可选符号失败导致整个工作区网格被禁用；另有 21 项网格完整性测试，CI 共执行 53 项。

## 工作区位置与边距自定义（ColorOS 17.5.5）

新增四侧独立调节：**左 / 上 / 右 / 下**，单位 dp，可输入 **-160～160** 的带符号整数。数值是在原生 Workspace padding 上的*增量*；全 0 完全保留系统默认几何。正数增加该侧留白、负数减少；例如 `左 +20，右 -20` 会将工作区向右移动约 20 dp（如果右边原始留白足够），而不改变网格行列或图标数据库坐标。

设置页新增独立的「应用边距并重启桌面」和「恢复系统默认边距」入口；边距配置存于 `/storage/emulated/0/Android/data/com.android.launcher/files/launcher_workspace_margins`，格式为 `left top right bottom`，**不与旧版 `launcher_grid` 混用**。调整不会触发网格迁移或自动重排，也不会修改文件夹、Hotseat、所有应用和小组件面板本身的边距。工作区旋转时会沿当前物理方向重新应用相同的四侧 dp 增量。

### 实现链路

直接核实用户提供的 `OplusLauncher-17.5.5.apk`：`Workspace.setInsets(Rect)`（`classes.dex PC 0x000e`）读取 `DeviceProfile.mWorkspaceParam` 的 `l5c.h():Rect`，在 `0x001a` 调用 `View.setPadding(left, top, right, bottom)`，之后才继续设置分页间距并更新页/小组件大小。该原生 Padding 方法同时被 WidgetSheet 使用，因此模块**不 Hook 共享的 `l5c.h()`**；只在 `Workspace.setInsets` 执行完之后，对真正的 `Workspace` View 追加边距。这一层控制分页布局及拖拽坐标使用的 View Padding，而不是通过 `translationX/Y` 制造视觉与命中区域不一致。

每次 `setInsets` 都以原生 padding 作为基准，不累加旧值。正边距总和最高约占可用视口对应轴的 72%，防止 8×4/4×8 网格被极端设置压缩到不可操作。会生成只读诊断 `launcher_margin_status`，包括配置的 dp 增量、原生 padding(px) 和实际 padding(px)。

**验收建议**：先从 `左 12 / 上 12 / 右 0 / 下 0` 进行轻量测试，检查图标实际偏移、水平换页、拖拽放置、Hotseat 是否保持原位，再旋转到竖屏核验。当前仅证明 17.5.5 相关 Hook 入口和配置逻辑成立，最终效果需要真机验收。

## 修复 17.5.5 竖屏仍保持 8×4（显示方向与模型边界）

在真实 17.5.5 DEX 中，`uh9.c():boolean` 只检查 `yn9.m()` 和 `i94.s(yn9.e)`，**不是**当前屏幕方向检测；`InvariantDeviceProfile.setColumnsAndRows` 仅在该特性判断为真且原始列数大于行数时才交换两轴。之前的 `effectiveWorkspaceGrid()` 错误地使用 `uh9.c` 当竖屏判断，`yn9.v(Pair)` 更是始终返回 8×4，导致横竖屏不同步。

本次增加 `GridOrientation.current(Context, ...)` 以默认显示器的 `Display.getRealSize()` 为主、应用 `Configuration.orientation` 为后备，统一确定**横屏 8×4，竖屏 4×8**。同一逻辑接入 IDP 最终列行、`OplusCellLayout.setGridSize` 两种重载、`setGridCellSize`、构造时工作区检查以及 `yn9.v(Pair)` 的最终模型边界。真实屏幕尺寸无法读取时仅参考 native 结果的轴方向，不误将 `uh9.c()` 当作方向。

保留 `uh9.a(0/1)` 的原生**显式方向参数语义**：该 API 的 0/1 要求固定方向的布局尺寸，不代表当前物理方向。本次不更改 `vCellX/vCellY` 或卡片跨度，也不自动搬运可能冲突的天气卡片或图标。新增 8 项单元测试覆盖 0°/90°/180°/270° 对应的屏幕宽高、方向配置与真实显示尺寸不一致时以物理尺寸为准、屏幕未就绪时的回退。

当前源码的修改必须通过 CI **和**真机方向切换确认；此前 CI #10 是过时提交，不能代表此修复。

## 针对真实 17.5.5 APK 的 DEX 符号适配

2026-11-22 从目标 APK `OplusLauncher-17.5.5.apk` 的二进制 `AndroidManifest.xml` 验证：版本 `17.5.5`，versionCode `170050005`，SHA-256 `b487204310bb5c8180ac3092950a258581c4468793541bbcea21f9aa6dd67b56`。直接解析四个 DEX，确认旧归档 **17.3.9** 的关键混淆 owner 已全部变化：

| 功能 | 旧 17.3.9 | 实际 17.5.5 |
|---|---|---|
| 模型加载最终边界 | `j5.i.I(Pair)` | `yn9.v(Pair)` |
| 网格旋转/缓存 | `p5.c.a(int)` / `p5.c.d()` | `uh9.a(int)` / `uh9.c()` |
| 读取原生网格 | `com.android.launcher.c6.b(Context)` | `wjb.b(Context)` |
| COMPACT/FREE 管理器 | `LayoutUtilsManager.b/c/e` | `com.android.launcher.layout.i.b/c/e` |
| 持久化网格配置 | `LayoutSettingsHelper.saveLayoutInfo` | `zv5.o(Context,int,int)` |
| 原生网格缓存字段 | `c6.f1273g/h` | `wjb.h/i` |
| Loader 状态日志应用上下文 | `LauncherApplication.getAppContext()` | `LauncherApplication.q`（Context 静态字段） |

**DEX 直接证据**：17.5.5 的 `OplusLoaderCursor.checkItemPlacement` FREE 分支（`classes.dex` PC `0x0261`）调用 `yn9.v(Pair)`。该方法在无需 seamless rotation 时直接返回传入的 IDP 网格；其他路径经 `uh9.a(int)` 访问独立旋转网格缓存。`OplusInvariantDeviceProfile.injectPreInitGridExt` 直接读取 `wjb.b(Context)` 并写 `wjb.h/i`。

新 Hook 同时支持两组**已知 owner + 完整签名**。它不搜索 DEX 中任意匹配签名的方法，不使用机器码或固定偏移；遇到未知第三个版本仍需检查其 APK。模型加载边界日志现在可以通过 17.5.5 的实际 `LauncherApplication.q` Context 写入。

另外，`WorkspaceItemProcessor.processItem` 把 `itemType=5/99` 交给 `processWidget()`，`itemType=100` 交给 `processCard()`。普通小组件保护由错误的 4 修正为 5；天气卡片则属于卡片加载分支，且需要同时确认模型边界与其他图标的占用冲突。

**本次是依据 17.5.5 的真实 DEX 修正 Hook 安装位置，不代表已有天气卡片和图标的重复坐标已被自动恢复。** 需要安装测试后以 `launcher_loader_status` 及 `OplusLoaderCursor` 的真实日志确认加载器边界是否为 8×4；重启前备份桌面。

## 7、8 列启动重排和小组件消失：数据保全修复

归档源码显示 `OplusLoaderCursor.checkItemPlacement()` 的 COMPACT 分支会先找空位、`setCellXY()`、`markUpdate()`，然后由 `commitUpdate()` **永久写回新坐标**；FREE 分支才保留已有位置。旧版模块把 `LayoutUtilsManager.b/e/c` 三个 Hook 放在同一个 `try` 中，某个符号失败就连 `c()` 的模式判断也无法安装。

本版按函数签名**独立安装**模式判断、模式设置和偏好初始化；在 `b(Context)` 读取偏好前写入 FREE。额外为 `OplusLoaderCursor.commitUpdate()` 增加防线：当模块明确开启保留空位时，不允许提交它的自动 COMPACT 坐标重排队列；普通拖拽仍通过 `ModelWriter` 保存，不依赖这个加载器方法。若触发阻断，会记录 `blocked ... automatic COMPACT coordinate DB updates`。

在 6×4 → 8×4、数据库文件、设备类型和 Hotseat 未发生变化时，`GridSizeMigrationTaskV2` **不再因为小组件的旧竖屏坐标、跨格尺寸或已有位置冲突而回退执行原生迁移**。旧记录原样保留，只更新网格迁移状态，并分别保护一参数与非预览的二参数迁移入口。缩小网格或跨数据库/设备类型迁移仍遵循原生流程。

对桌面小组件（已核验的 `itemType=4/99`），启动时遇到暂时缺失提供者、恢复标志不完整、非法跨度或占位冲突等明确原因，会**保留数据库行供后续恢复**；这并不意味着暂时缺少提供者的小组件能够立刻显示。实际卸载后的孤立小组件记录可能继续保留，需要用户主动清理。这是优先保护数据的取舍。

**验证范围**：修复基于 ColorRE 中归档的 17.3.9 源码，不代表已经适配设备实际 Launcher 版本。CI 只能覆盖结构化测试和 APK 打包；恢复第 7、8 列和小组件可见性需要用实际设备验证。之前已经删除的行无法自动重建。

## 图标放置与挤压：工作区占用矩阵同步

当配置从默认 6×4 扩展到 8×4 时，`CellLayout.setGridSize(int,int,boolean)` 会创建新的 **全空** `mOccupied` 和 `mTmpOccupied`。如果页面已经绑定图标，视图仍显示在原位置，但拖拽的 `isRegionVacant()`、`findCellForSpan()` 和 `OplusCellLayout.performReorder()` 会使用已清空的矩阵，错误判定已有图标位置为可放置区域。这是图标重叠与原生挤压失效的一条明确调用链。

新增 `WorkspaceOccupancySync`：在工作区网格重设完成后，根据 **实际绑定的 `mShortcutsAndWidgets` 子视图**及 `CellLayoutLayoutParams.cellX/cellY/cellHSpan/cellVSpan` 重建规范占用快照，同步两套原生矩阵，并保留原生 `performReorder` / 文件夹创建 / 图标挤压处理。跳过非网格装饰子项；如果正在拖拽或重排预览，不覆盖临时占用状态。不会自动移动图标、变更桌面数据库坐标或覆盖原生拖拽动画。

遇到已有图标互相重叠时，将占用单元标记为已使用并记录 `workspace collision detected`；遇到无效/越界的现存子视图，拒绝未经验证的矩阵重建并记录错误，避免将未知状态写入占用矩阵。新增加 10 项纯 Java 测试，覆盖第 7/8 列、跨格小组件、已有重叠、空位、异常跨度以及原生 6×4 → 8×4 重建。

**仍需设备验收**：本次只修复网格重置引起的占用矩阵不一致。另有设备版本、桌面模式、临时拖拽状态差异需要实机核验，不能因 CI 成功宣称所有挤压行为已适配。

## 标准桌面加载边界：6 列截断修复

ColorOS 标准模式启动实际采用 `OplusStandardLoaderTask → OplusBaseLoaderTask.run() → loadAndBindWorkspace() → OplusLoaderCursor`，而不是此前只考虑的 AOSP `LoaderTask.loadWorkspace()`。图标、文件夹和部分空间分配在最终校验时使用 `j5.i.I(Pair)`。此方法在没有启用平板无缝旋转的条件下直接返回传入的 IDP 网格尺寸，因此即使 `p5.c.a(0)` 被改为 8×4，加载器仍有机会使用旧的 6×4。

修复将 **`j5.i.I(Pair)` 固定为自定义横屏网格**，并在设置界面显示 `native_loader_bounds` 与 `effective_loader_bounds`，同时保留先前工作区 `actual_workspace` 报告。在备用 AOSP 加载器的网格迁移入口，如果 IDP 的目标尺寸不是配置的规范横屏尺寸，阻止它以 6×4 或暂时转置的 4×8 执行破坏性迁移。

新的 CI 包含 21 项布局回归测试，尤其覆盖 6×4 → 8×4、竖屏 IDP 转置与非法缩小迁移判断。仍需真机验证图标是否保留，单元测试不能代替设备检查。

## 系统性一致性修复

- 原生排布模式由 `LayoutUtilsManager` 的 `layout_is_compact` 持久化字段和运行时选择器共同控制。启用「保留空位」会令启动加载器及拖拽使用 FREE 分支，而非只拦截界面填空行为。
- 不允许 `OplusUnionGridCompatHelper.updateGridIfNeeded()` 在开启自定义尺寸时执行旧系统默认网格改写。
- 仅当完整检查横屏和竖屏坐标、尺寸、跨格范围和同屏占用冲突后，才跳过网格扩展迁移。
- 配置网格内的条目若在加载时出现瞬时位置冲突，保留数据库记录并输出日志，待定位而不静默删除。
- 如果原生迁移失败，阻断该次 `LoaderTask` 随后发起的自动 `create_empty_db`（仍记录错误），避免迁移问题扩大为桌面数据清空；不影响主动重置桌面。
- 配置在桌面进程启动时读取一次；修改配置必须重启 Launcher，不允许运行中动态切换网格。
- 开关恢复原生排列时会恢复此前保存的 COMPACT/FREE 选择，不会把系统设置永久改为 FREE。
- `GridIntegrity` 使用纯 Java 边界与占用校验，21 个 JUnit 回归测试随 CI 执行；这个测试不能替代设备端的重启和旋转验收。

完整范围与剩余验证点参见 [布局生命周期审计](docs/LAYOUT_AUDIT.md)。

## 运行状态诊断

设置界面点击 **刷新工作区实际网格**，模块从 Launcher 自身进程生成的 `launcher_grid_status` 读取：

```text
stage=page_created
configured_landscape=8x4
expected_workspace=8x4
actual_workspace=8x4
match=true
```

`expected_workspace` 是按照 ColorOS 平板方向转换计算的目标行列，`actual_workspace` 才是运行中的 `OplusCellLayout.getCountX()/getCountY()`。竖屏（使用 ColorOS 新平板布局）时，`8x4` 的横屏配置可转换为 `4x8`。如果报告缺失，先核对 LSPosed 作用域是否包含 `com.android.launcher`；如果 `match=false`，保存本次状态和 `OLauncherMod` 日志以定位后续覆盖点。

## 项目结构

```text
src/com/yu4032/olaunchermod/LauncherModule.java  LSPosed Hook
src/com/yu4032/olaunchermod/MainActivity.java    布局设置
AndroidManifest.xml                              应用声明
src/main/resources/META-INF/xposed/              模块信息与作用域
res/                                             图标
```

目标包名：`com.android.launcher`。本项目模块应用标识：`com.yu4032.olaunchermod`。

## 更新注意事项

此版本调整了模块自身的 Android 包名与配置文件名，属于**新的模块安装标识**。旧版无法直接就地覆盖；需安装新模块、在 LSPosed 中勾选 `com.android.launcher` 作用域，并重新保存网格设置。

升级前备份 Launcher 数据和布局。请优先验证 6×4 → 8×4、在新增列移动图标、重启桌面后的坐标、文件夹与小组件的正常行为。

## 构建

依赖 JDK 17、Android SDK 36、Gradle 8.14.8、AGP 8.11.1，以及 `compileOnly 'io.github.libxposed:api:102.0.0'`。

本地安装好 Gradle 和 Android SDK 后运行：

```bash
gradle assembleDebug
```

构建产物位于 `build/outputs/apk/debug/`，Debug 构建由 Android Gradle Plugin 自动使用开发调试密钥签名，可直接安装测试，不代表正式发布签名。

### GitHub Actions

工作流为 [Android CI](.github/workflows/android.yml)，在以下情况自动执行：

- 推送到 `main`；
- 创建或更新 Pull Request；
- 合并后，可从 **Actions → Android CI → Run workflow** 手动触发。

CI 会安装 Android SDK 36、编译 Debug APK、验证 APK 中的 `META-INF/xposed/{java_init.list,module.prop,scope.list}` 与模块入口，然后上传名为 `OLauncherMod-debug-<run_number>` 的 Artifact（保留 30 天）。从 Actions 的构建记录底部下载 ZIP，解压得到 `OLauncherMod-debug.apk`。

**CI 编译和打包通过不等于原生 Hook 已经在 ColorOS 真机验证。** 安装前请备份桌面数据库。
