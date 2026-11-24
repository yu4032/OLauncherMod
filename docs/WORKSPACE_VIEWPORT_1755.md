# ColorOS 17.5.5 竖屏 4×8：按剩余可用高度测量工作区

## 确认的 DEX 调用链

分析用户提供的 `OplusLauncher-17.5.5.apk` `classes.dex`：

- `Workspace.setInsets(Rect)`：PC 0x000e 读取 `l5c.h():Rect`，PC 0x001a 调用 `View.setPadding(left,top,right,bottom)`，保存 `PagedView.mInsets` 并设置页间距。
- `PagedView.onMeasure(int,int)`：PC 0x0038–0x0061 **减去了 mInsets 的四侧值和 Workspace 左右 Padding，但纵向仅减去 mInsets.top/bottom，并没有扣除 getPaddingTop()/getPaddingBottom()**。
- `PagedView.onMeasure` PC 0x0096/0x009a 生成子页面测量规格，并在 PC 0x009e `measureChildren()`。
- `OplusCellLayout.onMeasure`：调用父类 `CellLayout.onMeasure`。
- `CellLayout.onMeasure`：PC 0x007b 将真实页面宽高传给 `injectOnMeasureInit`；PC 0x0086–0x009e 按 `mCountX/mCountY` 和 `mBorderSpace` 重新计算 `mCellWidth/mCellHeight`，**但当 mFixedCellWidth、mFixedCellHeight 都非零时可能跳过该重算**。
- `OplusCellLayout.injectOnMeasureInit(int,int)` 直接保存 `mChildrenWidthWithoutPadding/mChildrenHeightWithoutPadding`。

这解释了此前竖屏网格 **4 列 × 8 行已经正确，却仍因页面以过大的纵向测量规格分配单元格，侵入上方保留区域与下方 Dock** 的可能路径。

## 修复策略

- 在 `OplusCellLayout.onMeasure(int,int)` 前，只处理实际属于 Workspace 的 `OplusCellLayout`；其他 CellLayout（文件夹、Hotseat 等）不变。
- 仅在**物理竖屏且当前网格行数 ≥6** 时，使用当前页面 MeasureSpec 的真实高度作为基础，**再扣除 Workspace 已应用的上/下 Padding**，得到可分配给图标网格的剩余高度。已经由父 `PagedView` 扣除的系统 Insets 不重复采用完整屏幕高度作为计算基准。
- 使用减少后的 EXACTLY 子页面高度规格继续执行原生 `onMeasure`，让 `CellLayout` 自己按 8 行和原生 `mBorderSpace` 重新计算单元格尺寸；不改 `mCountX/mCountY`。
- 若原生已固定的单元格高度超出当前计算的行高上限，仅释放旧的固定单元格宽/高，使这一次原生测量能够重新计算；**不会对所有页面无条件重置尺寸**。
- 极端参数导致剩余空间小于行数时，不创建无效的 0px 行或写入布局状态；保留原生测量并通过日志诊断。
- 诊断：`launcher_viewport_status` 记录 `paged_child_height_px`、`workspace_padding_top_px`、`workspace_padding_bottom_px`、`remaining_grid_height_px`、`rows` 和 `actual_cell_height_px`。
- 不读写桌面数据库、不触发网格迁移、不改变小组件跨度或拖拽坐标；由真实 View 的测量系统完成重排布局。前一个版本已生效的 `GridOrientation`、`yn9.v(Pair)` 不变。

## Compose UI

本 PR 同时替换旧 Java Activity 布局为 **Compose + Material3 的 ColorOS 风格界面**，网格主页与边距页都用 Kotlin Compose 实现。包含横竖屏分段切换、带 dp 数值滑块、±1dp、可编辑数值弹窗、单项/整页重置、保存并重启、非阻塞的 Root 状态读取与诊断。原来配置文件及格式保持不变。

暂不直接引入第三方 COUI 1.1.0：当前该库的 Compose/Kotlin 依赖跨度明显高于项目原有 AGP 8.11.1 + compileSdk 36；在维持现有 Hook 工程兼容性的情况下选用 Kotlin 2.2.20 + Compose BOM 2025.09.00。视觉遵循 COUI 的圆角分组、低密度强调和数值调节风格，但**不是系统私有 COUI 控件**。

## 真机验收

1. 在已生效的版本基础上先使用全 0 边距。横屏 8×4 不应变化；竖屏 4×8 的第 8 行必须仍处于 Dock 上方，顶部第一行不能与顶部系统区域重叠。
2. 竖屏分别测试顶部 +40dp、底部 +40dp，工作区单元格高度应自动缩小而不是把最后一行向屏幕外推。
3. 横屏再测试上述边距：不影响原来 8×4 的网格与拖动放置。
4. 比较 `launcher_viewport_status` 中页面原始高度、Workspace 保留高度和可用高度；确认 `rows=8` 且 `actual_cell_height_px * 8 + borderSpaceY*7` 未超过剩余高度。
5. 验证多屏滑动、拖拽、4×2 天气卡片、横竖屏来回旋转，以及从设置页保存后重新启动的恢复行为。
6. 仅有 CI 的算法和构建成功不能证明真实设备 UI 没有越界；验收前不要合并发布。
