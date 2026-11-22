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

本模块现在同步 `p5.c.a(0)`（横屏）和 `p5.c.a(1)`（竖屏）及其缓存，确保加载阶段采用自定义网格边界。额外记录工作区放置校验失败日志（tag: `OLauncherMod`）。保留原生重叠/越界校验，不拦截所有删除行为。

**注意：此修复阻止后续重启继续发生相同的数据删除，但不会自动恢复之前已从桌面数据库删除的图标记录。** 已删除的快捷方式应从桌面备份恢复，或者重新添加到工作区。更改前请先备份桌面布局。

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
