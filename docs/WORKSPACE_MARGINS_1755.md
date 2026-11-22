# Workspace 边距独立配置（17.5.5）

## 原生调用链验证

归档实包：`OplusLauncher-17.5.5.apk` `classes.dex`。

```text
OplusWorkspace.setWindowInsets(Rect)
  -> Workspace.setInsets(Rect)
       -> BaseActivity.mDeviceProfile
       -> DeviceProfile.mWorkspaceParam  [l5c]
       -> l5c.h(): Rect                 [DEX 0x000e]
       -> View.setPadding(L,T,R,B)     [DEX 0x001a]
       -> PagedView.mInsets.set(Rect)
       -> PagedView.setPageSpacing(...)
       -> updateCellLayoutPadding()
       -> updateWorkspaceWidgetsSizes()
```

`l5c.h()` 另外被 `Workspace.setWorkspacePivotY()`、`BaseWidgetSheet.doMeasure()`、`OplusWidgetsFullSheet.onMeasure()` 等调用。直接 Hook 返回值会扩散到小组件面板的测量，不符合仅调整工作区位置的目标。因此 Hook `Workspace.setInsets(Rect)`：先执行原生布局更新，再对 `Workspace` 的 Padding 叠加用户四侧 delta。

## 配置

工作区共享目录 `launcher_workspace_margins`，一行四个带符号整数：

```text
left top right bottom
20 8 -20 0
```

默认 `0 0 0 0`。范围各 `-160..160` dp，向上/向左可通过对应负值缩小原生留白。因 Android View 不支持负 padding，每侧最小为 0px；当左右或上下额外边距过大时进行比例限制，至少保留轴向视口 28% 的可用空间。请注意小屏设备上的实际偏移可能因此小于输入的 dp 数。

- 与 `launcher_grid` 完全独立，不触发 DB 迁移、Loader 删除或坐标 UPDATE。
- 只改变真实 `Workspace` 的 Padding，不对 `OplusCellLayout` 进行 `setTranslation`；使页面几何和拖拽坐标基于同一 View 布局。
- 原生每次 `setInsets` 会重设基础 Padding，Hook 不会重复累计配置；横屏/竖屏均按真实系统各自的基础 Padding 追加同一组数值。
- 在设置页保存后主动重启桌面进程，避免正在拖动图标时变更网格和边距。
- 诊断文件 `launcher_margin_status` 由 Launcher 自己的 external files 目录写入。内容包括 dp 值、native/actual padding。

## 验收

1. 边距默认 0：确认与此前已验证的 8×4 横屏、4×8 竖屏完全一致。
2. 左 +20、右 -20：观察工作区整体右移（如果 native 右侧留白不足，右值会裁至 0），Hotseat 不移动。
3. 上 +20、下 -20：观察工作区竖直位置和图标触摸区域一致。
4. 在两种方向测试切页、长按拖动、卡片/小组件横跨格子；边距只影响显示几何，不得修改数据表 `singledesktopitems`。
5. 读 `launcher_margin_status` 查看实际 Padding，确认首次启动与旋转后的结果并没有在每次调用中重复叠加。

