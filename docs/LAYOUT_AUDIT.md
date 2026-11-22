# ColorOS Launcher 17.5.5：布局生命周期一致性审计

来源：`y700-launcher-pack.zip` 内的 ColorOS Launcher 17.5.5 JADX 反编译源代码，以及本仓库的 `LauncherModule`、`MainActivity` 与 Gradle/Actions 配置。反编译控制流可能存在 JADX 误还原，方法签名、调用关系与数据库读写的交叉证据可信度高于单个异常分支。

## 边界模型

同一组自定义网格尺寸必须贯穿以下链路：

1. **配置**：`launcher_grid` → `layout_cellcount_x/y`（`com.android.common.util.f7.e`）→ `LayoutSettingsHelper.saveLayoutInfo()`。
2. **IDP**：`OplusInvariantDeviceProfile.injectPreInitGridExt()` → `InvariantDeviceProfile.setColumnsAndRows()` → `DeviceProfile`。
3. **方向**：`p5.c.a(0/1)` 的横竖屏缓存；`j5.i.I(Pair)` 有条件读取该缓存。
4. **工作区**：`OplusCellLayout` 构造、`setGridCellSize()`、二参数 `setGridSize()` 和基类三参数 `CellLayout.setGridSize()`，最终 `mCountX/mCountY` 和 `GridOccupancy` 必须匹配。
5. **标准模式真正的加载器**：`OplusStandardLoaderTask → OplusBaseLoaderTask.run() → loadAndBindWorkspace() → OplusLoaderCursor`。与基础 `LoaderTask.loadWorkspace()` 不同，标准模式通过逐页加载，不一定经过 `GridSizeMigrationTaskV2.migrateGridIfNeeded()`；原先仅检查迁移入口的保护覆盖不足。
6. **最终数据库边界**：`j5.i.I(Pair)` 会按平板无缝旋转开关选择 `p5.c.a(0)` 或直接返回传入的 IDP 尺寸。此处同时服务 `OplusLoaderCursor.checkItemPlacement()`、基础 `LoaderCursor` 和 `l4.b/l4.e` 空间分配。必须直接统一规范横屏目标尺寸。
7. **数据加载**：`OplusLoaderCursor.checkItemPlacement()` 按布局模式分别走 FREE/COMPACT 逻辑，并判断边界和占用；`OplusBaseLoaderTask` 的局部页面加载同样会使用该逻辑。
8. **迁移与删除**：`DeviceGridState` → `GridSizeMigrationTaskV2`；`LoaderCursor.markDeleted()` → `commitDeleted()`；迁移返回 false 时 `LoaderTask.loadWorkspace()` 调用 `create_empty_db`。
9. **配置变更**：模块设置界面在保存后强制结束 Launcher 进程；运行中的 Hook 使用一次加载的不可变配置，避免数据库/界面看到两组尺寸。

## 新增：图标放置及挤压路径

`CellLayout.setGridSize(int,int,boolean)` 在源码中直接创建新的 `GridOccupancy(mCountX,mCountY)` 赋给 `mOccupied/mTmpOccupied`，**不会重新遍历已有页面子视图重建占用**。新版本为了扩展 8 列多次调用此方法，构成运行中视图仍存在、空位查询已被重置的不一致。

后续 `CellLayout.findCellForSpan()` → `mOccupied.findVacantCell()`，`CellLayout.isRegionVacant()` → `mOccupied.isRegionVacant()`，`OplusCellLayout.performReorder()` → 以 `mOccupied/mTmpOccupied` 为基础的 `findSequenceReorderSolution()`；这条链会把现有图标位置错误识别成空位，既导致覆盖，也使原生挤压逻辑找不到被挡住的对象。

修正：`WorkspaceOccupancySync.afterGridReset()` 在 `setGridSize` 正常执行之后，依据已绑定的 `ShortcutAndWidgetContainer` 及其网格 LayoutParams 生成占用矩阵，并原子校验新旧网格尺寸后同步 `mOccupied`、`mTmpOccupied`。只在真实工作区执行；拖拽或临时重排期间不重建，以免恢复被主动释放的拖动位置。不取代原生 `performReorder`、文件夹落点判断或 `ModelWriter` 持久化逻辑。

新增 `WorkspaceOccupancyTest` 10 项测试：6→8 列、7/8 列已有图标、跨格小组件、已有碰撞记录、正常空位、边界错误、禁止半写入等。静态实验只证明矩阵恢复算法，不证明设备上每个拖拽变体都已通过。

## 审计矩阵

| 优先级 | 路径 / 风险 | 处理方式 | 验证层级 |
|---|---|---|---|
| P0 | `OplusBaseLoaderTask` 标准模式未使用基础 `LoaderTask.loadWorkspace`，此前迁移补丁不覆盖主路径 | 审查实际启动与逐屏加载流程，并直接统一 `j5.i.I(Pair)` 最终边界 | 反编译主调用链已验证；待真机 |
| P0 | `j5.i.I(Pair)` 在特定设备配置下直接返回 6×4 的 IDP，即使 `p5.c.a(0)` 为 8×4 | 在唯一最终规范化入口返回配置的横屏网格；记录原生值和强制结果 | 已加 4 项回归测试；待真机 |
| P0 | `OplusLoaderCursor.checkItemPlacement` 使用旧横屏 6×4，把第 7、8 列识别为越界 | 同步 `p5.c.a(0/1)`、IDP、工作区尺寸 | 方法调用核对；待设备复测 |
| P0 | `markDeleted("Item position overlap")` 会进入 `commitDeleted` 永久删记录 | 对 **在配置范围内且仅因位置冲突被拒绝** 的工作区条目保留数据库记录并记录 ERROR；不会将冲突项强行添加到工作区 | 单元测试 + 设备复测 |
| P0 | `migrateGridIfNeeded=false` 后 `LoaderTask` 请求 `create_empty_db` | 只阻断**此次迁移失败之后同一加载线程**的清库调用，不影响主动清空数据库 | 静态调用链；待异常注入复测 |
| P1 | `LayoutUtilsManager.c()` 仍可为 COMPACT；先前 `fillUpEmptyCell` Hook 无法控制模型加载 | 对开启「保留空位」的配置持久化 `layout_is_compact=false`，同步运行时 FREE；关闭选项恢复之前记录的原生模式 | 源码检查；待设备复测 |
| P1 | `OplusUnionGridCompatHelper.updateGridIfNeeded()` 可重新保存旧版兼容网格 | 仅在自定义布局开启时跳过旧版尺寸转换 | 源码检查；待设备复测 |
| P1 | `preserveExpansion` 只验证当前方向边界，漏检重叠、另一方向坐标与跨格组件 | `GridIntegrity` 验证两方向范围、同屏重叠、非正跨度、空坐标与整数溢出；不满足则交由原生迁移 | JUnit 回归测试 |
| P1 | Hook 的反射或日志异常可直接破坏 Loader/Workspace 流程 | 运行时 Pair/数据库游标反射、诊断写入均有独立错误处理；日志失败不妨碍原调用 | 代码审查、CI 编译 |
| P2 | 动态每两秒读取配置，可在拖拽/绑定中切换目标尺寸 | 配置在 Launcher 进程启动时只解析一次，保存设置必须重启桌面 | 代码审查 |
| P2 | 全局 `fillUpEmptyCell` 拦截可能影响非工作区容器 | 仅拦截 `isBelongToContainerType(0)` 的工作区页面 | 源码检查 |
| P2 | 工作区网格诊断每页多次写入文件 | 仅在预期/实际行列发生变化时更新状态 | 代码审查 |

## 保持的行为边界

- **不会**全局屏蔽 `commitDeleted`、`LoaderCursor.markDeleted` 或 `GridSizeMigrationTaskV2`。
- **不会**自动重写 `singledesktopitems` 的 `cellX/cellY/vCellX/vCellY`，更不会自动挤满空位。
- 无法通过无损扩展验证的布局仍交给原生迁移；系统主动重置数据库不受迁移失败兜底影响。
- 文件夹、Hotseat 与应用抽屉不是工作区网格，不能被套用 8×4。
- 某个图标因真实位置冲突而被拒绝加载时，数据库记录会保留，但它可能在界面上暂时不可见。日志中会出现 `preserved rejected workspace DB row`，需要解决冲突。

## 单元测试覆盖

`tests/com/yu4032/olaunchermod/GridIntegrityTest.java` 覆盖：扩大列数、稀疏位置、缩小/不变尺寸、跨界小组件、同屏重复位置、跨屏相同位置、竖屏越界、竖屏重叠、半缺失坐标、非正跨度、整数溢出、布局删除保护条件与不保护其他删除原因。

当前共 21 项 JUnit 回归测试。在 CI 中执行 `gradle testDebugUnitTest assembleDebug`，成功之后再验证 APK 中的 LSPosed 入口和作用域。

## 真机验收序列

需先备份 Launcher 数据库和桌面布局。以 8×4 横屏网格为例：

1. 将普通应用图标分别置于第 7、第 8 列；重启 Launcher 两次，检查数据库条目及实际坐标是否还在。
2. 在第 7/8 列加入文件夹和可跨格的小组件，验证保存、重启与大小调整。
3. 在第一屏及第二屏制造正常空位，删除/移动图标、合并和拆分文件夹，确认 FREE 排列不会自动压缩。
4. 旋转至竖屏、回到横屏并再次重启，核对 `vCellX/vCellY` 与 `p5.c.a(1)`。
5. 关闭「保留空位」，检查恢复原生排列模式；再开启，检查 FREE 路径。
6. 同时检查 `launcher_grid_status`、`launcher_loader_status` 和 `OLauncherMod` 过滤日志，并对比数据库行数。出现 `native grid migration failed`、`preserved rejected workspace DB row` 等警报时，停止追加布局更改，先核查数据。

## 尚不能由静态审计证明的事项

- **没有真机数据库快照/ADB 日志**：不能断言每个设备型号、页面模式或横竖屏状态均已覆盖。
- **反编译代码存在不完整还原**：部分 `OplusLoaderCursor` 分支需要以目标 APK 的 dex 字节码或实机行为最终核定。
- **第三方修改/系统 OTA**：类名、方法签名、配置存储和 UI 行为变化需重新审计，不能只靠 CI 通过证明 Hook 仍有效。
- **现存数据损失**：任何已经被 `commitDeleted` 实际删除的数据库记录都无法由 Hook 自动恢复；需从之前的备份还原。
- **原生迁移仍可能改动布局**：当缩小网格、检测到冲突、组件越界或设备类型变更时，模块不跳过原生迁移。此类变更需先备份验证。

以上均以实际文件中的行为作为依据；未对所有 Android 桌面类型的任意组合声称兼容。
