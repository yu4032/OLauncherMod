# OLauncherMod

开发中,面向ColorOS17的桌面自定义模块。

- 目标应用：`com.android.launcher`（ColorOS 17.3.9）
- minSdk 29 / targetSdk 36

## 工作方式

| Hook | 作用 |
| --- | --- |
| `InvariantDeviceProfile.setColumnsAndRows` | DeviceProfile 使用配置的列行 |
| `OplusCellLayout.setGridSize` | Workspace / CellLayout 实际网格同步 |
| `Launcher.onCreate` | 启动时按当前网格重排 `launcher.db` 图标坐标 |

配置位于 `/storage/emulated/0/Android/data/com.android.launcher/files/y700_grid`，一行 `列 行`，横屏语义；竖屏由 Launcher 转置。范围：列 4–8，行 3–8。模块每 2 秒最多读取一次，保存后重启桌面生效。

重排只处理 `singledesktopitems` 中 `container=-100` 的条目，按 span 做首次适配，保留文件夹 span 与子项关系。同一网格只执行一次，记录在 SharedPreferences `y700_reflow`。

## 目录

```
src/local/y700/launcher/LauncherModule.java   网格 hook 与重排
src/local/y700/launcher/MainActivity.java     网格设置界面
res/                                          模块图标
META-INF/xposed/                              模块元数据（API 102）
AndroidManifest.xml
reflow.py                                     离线重排脚本
```

## 离线重排

```
python3 reflow.py launcher.db 8 4
```

## 构建

依赖 `io.github.libxposed:api:102.0.0`，产物为模块 APK（`out/Y700Launcher102.apk`）。
