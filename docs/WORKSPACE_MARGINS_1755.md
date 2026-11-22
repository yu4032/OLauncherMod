# 工作区位置自定义：LiquidDock 风格设置（17.5.5）

## 基于 LiquidDock 原始实现的设计

来自用户的 `yu4032/LiquidDock` 仓库，按当期 `main` 源码核实：

- `DeclaredSettingsPages.kt`：`Page.GridLandscape`、`Page.GridPortrait` 两页；各有“工作区位置”区段，与独立“页面指示器”区段。
- `SettingsOptionSpecs.kt`：每个方向独立的 `horizontal_distance` / `top_distance` / `bottom_distance`，水平参数描述为“同时调整左右两侧的水平距离”。
- `ConfigSchema.Grid`：这六项默认 0dp，范围 -600..600dp。
- `SettingsControls.IntSetting`：滑块，数值标签点击唤起精确输入，重置按钮，以及 ± 步进；滑动期间使用量化显示，避免无意义的反复写入。
- LiquidDock 使用 Jetpack Compose / MIUIX。OLauncherMod 当前为小体积纯 Java/LSPosed 模块，因此**实现同等交互而不引入整套 Compose/MIUIX**，并保留现有“保存后重启桌面”的安全约束。

## 配置格式与语义

文件 `/storage/emulated/0/Android/data/com.android.launcher/files/launcher_workspace_margins`。

**新格式**，共六个带符号整数，范围 -600..600dp：

```text
landscape_horizontal landscape_top landscape_bottom portrait_horizontal portrait_top portrait_bottom
20 -5 12 -10 15 5
```

- 横屏、竖屏的六个值分别独立。旋转仅**切换选用参数组**，不会把上边距变成左边距。
- 对当前方向的 `horizontal` 叠加左右原生 Padding，`top` 叠加顶部 Padding，`bottom` 叠加底部 Padding。0 即完全使用对应的系统原生值。
- 为防止异常值挤压图标，计算后的 Padding 最小为 0；若相对两侧的和超过轴向视口 72%，进行比例缩减。
- 参数变更只写入 Workspace 的 view Padding，不更改 `singledesktopitems`、网格行列、Loader 边界、Widget span 或文件夹/Hotseat Padding。
- 生效需要点击应用并重启桌面。滑块在设置页只改变本地草稿，避免影响进行中的拖拽或加载。

**旧版兼容**：四值 `left top right bottom` 仍可解析；在用户未明确编辑并保存新版六值前，保留原始四值含义及原文件格式。编辑时两屏的水平初值取旧左右值平均数；当旧左右不等时，保存前出现确认对话框，避免静默丢失不对称设置。

## 原生 17.5.5 布局入口

```text
OplusWorkspace.setWindowInsets(Rect)
  -> Workspace.setInsets(Rect)
       -> DeviceProfile.mWorkspaceParam / l5c.h(): Rect
       -> View.setPadding(L,T,R,B)
       -> page spacing / cell layout padding / widget size update
  -> OLauncherMod appends orientation-specific dp delta
       -> Workspace.setPadding(adjusted L,T,R,B)
```

没有修改原生 Padding 提供者 `l5c.h()`，因此不会扩散到 WidgetSheet 等其他调用者。旋转依赖先前统一的物理显示器方向判定。

诊断文件 `launcher_margin_status` 记录 `orientation`、`configured_dp`、`native_padding_px`、`actual_padding_px`。日志值是原生更新结束后的实际 Padding；不能拿它作为行列数或模型坐标的替代证据。

## 验收

1. 首先保存 `0 0 0 0 0 0`，确认横屏 8×4、竖屏 4×8 没有任何可见变动。
2. 横屏设置 `horizontal=20`，竖屏保持 0：横屏左右留白增加，旋转到竖屏后恢复原始留白。
3. 横屏顶部 +20、底部 -20：观察工作区纵向分布变化并测试触摸/拖拽命中，不改变 Hotseat。
4. 分别测试数值点击输入、滑块、±1dp、单项重置及重置当前方向；只有点击应用才改写文件。
5. 再用旧 `20 8 -10 0` 文件验证：读入前后的执行结果相同，触发用户编辑时才执行带确认的旧参数迁移。
6. 旋转后检查 `launcher_margin_status` 的 orientation 和 native/actual padding，测试切页、图标放置、跨格小组件/卡片是否仍可操作。

**暂未实现**：LiquidDock 中独立的页面指示器位置项，不应误描述成此 PR 的已有功能。工作区位置/边距真机效果尚待验收。
