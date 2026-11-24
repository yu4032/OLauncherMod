# ColorOS 17.5.5 竖屏 4×8：按剩余可用高度测量工作区

## 确认的 DEX 调用链

分析用户提供的 `OplusLauncher-17.5.5.apk` `classes.dex`：

- `Workspace.setInsets(Rect)`：PC 0x000e 读取 `l5c.h():Rect`，PC 0x001a 调用 `View.setPadding(left,top,right,bottom)`，保存 `PagedView.mInsets` 并设置页间距。
- `PagedView.onMeasure(int,int)`：PC 0x0038–0x0061 **减去了 mInsets 的四侧值和 Workspace 左右 Padding，但纵向仅减去 mInsets.top/bottom，并没有扣除 getPaddingTop()/getPaddingBottom()**。
- `PagedView.onMeasure` PC 0x0096/0x009a 生成子页面测量规格，并在 PC 0x009e `measureChildren()`。
- `OplusCellLayout.onMeasure`：调用父类 `CellLayout.onMeasure`。
- `CellLayout.onMeasure`：PC 0x007b 将真实页面宽高传给 `injectOnMeasureInit`；PC 0x0086–0x009e 按 `mCountX/mCountY` 和 `mBorderSpace` 重新计算 `mCellWidth/mCellHeight`，当两个固定单元格尺寸都非负时跳过重算，`-1` 表示由原生测量计算。
- `OplusCellLayout.injectOnMeasureInit(int,int)` 直接保存 `mChildrenWidthWithoutPadding/mChildrenHeightWithoutPadding`。

这解释了此前竖屏网格 **4 列 × 8 行已经正确，却仍因页面以过大的纵向测量规格分配单元格，侵入上方保留区域与下方 Dock** 的可能路径。

## 修复策略

- `launcher.xml` 中，`OplusWorkspace`、`OplusHotseat` 和 `OplusPageIndicator` 是 DragLayer 的直接子 View。竖屏下在 `OplusDragLayer.onLayout` 的原生布局完成后，以同一坐标系读取当次 Dock 和工作区边界，避免旋转时取到旧 Dock 高度。
- 网格上缘取工作区上缘（含原生 Insets 与设置的 Padding）和状态栏／刘海下缘中的较大值。网格下缘取工作区下缘、Dock 上缘和可用指示器上缘中的较小值。指示器压到 Dock 时，将整个 View 移至 Dock 上方。
- 只对自定义网格中实际属于 Workspace 的竖屏页面（至少 6 行）应用这段范围。按范围重新测量页面，原生 CellLayout 会按当前行数、内边距和行距计算单元格。测量期间临时清除固定高度，测量结束后恢复固定字段。
- `launcher_viewport_status` 记录状态栏下缘、Dock 上缘、指示器上缘、网格上下缘、行数及实际单元格高度。坐标均以 DragLayer 为原点。

## Compose UI

本 PR 同时替换旧 Java Activity 布局为 **Compose + Material3 的 ColorOS 风格界面**，网格主页与边距页都用 Kotlin Compose 实现。包含横竖屏分段切换、带 dp 数值滑块、±1dp、可编辑数值弹窗、单项/整页重置、保存并重启、非阻塞的 Root 状态读取与诊断。原来配置文件及格式保持不变。

暂不直接引入第三方 COUI 1.1.0：当前该库的 Compose/Kotlin 依赖跨度明显高于项目原有 AGP 8.11.1 + compileSdk 36；在维持现有 Hook 工程兼容性的情况下选用 Kotlin 2.2.20 + Compose BOM 2025.09.00。视觉遵循 COUI 的圆角分组、低密度强调和数值调节风格，但**不是系统私有 COUI 控件**。

## 真机验收

1. 在已生效的版本基础上先使用全 0 边距。横屏 8×4 不应变化；竖屏 4×8 的第 8 行必须仍处于 Dock 上方，顶部第一行不能与顶部系统区域重叠。
2. 竖屏分别测试顶部 +40dp、底部 +40dp，工作区单元格高度应自动缩小而不是把最后一行向屏幕外推。
3. 横屏再测试上述边距：不影响原来 8×4 的网格与拖动放置。
4. 比较 `launcher_viewport_status` 中状态栏下缘、Dock 上缘、指示器上缘和网格实际高度；确认 `rows=8` 且 `actual_cell_height_px * 8 + borderSpaceY*7` 未超过剩余高度。
5. 验证多屏滑动、拖拽、4×2 天气卡片、横竖屏来回旋转，以及从设置页保存后重新启动的恢复行为。
6. 在真机上检查指示器及其点击区域位于最后一行下方、Dock 上方。
