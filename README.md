# OLauncherMod

面向 ColorOS Launcher 的 LSPosed（API 102）桌面网格与自由布局模块。

> 代码中的 ColorOS 内部方法依据 Launcher 17.5.5 的反编译版本检查，尚未经过设备实测；其他版本可能需要适配。

## 功能

- 配置桌面网格的列、行（横屏语义：4–8 列，3–8 行）。
- 在 `OplusInvariantDeviceProfile.injectPreInitGridExt` 初始化之前同步 ColorOS 原生 `layout_cellcount_x/y` 设置，减少重启时恢复默认网格的情况。
- 当变更属于无损扩展（数据库与 Hotseat 配置相同，已有桌面项全部在新范围内）时保留原坐标，不执行会改变排列的网格迁移。缩小网格等情况仍使用原生迁移。
- 可选「保留空位」：拦截 `OplusCellLayout.fillUpEmptyCell` 的自动紧凑排列；不禁用通常的拖拽落点处理。
- **不会在桌面启动时直接更新图标数据库，不调用自动 `reflow`，也不会强制修改所有 CellLayout 的网格尺寸。**

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
