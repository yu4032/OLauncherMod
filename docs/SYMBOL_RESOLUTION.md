# Java/ART 函数名及签名解析（无固定偏移）

## 当前实现

OLauncherMod 使用 LSPosed API 102 对 `java.lang.reflect.Method` 和 `Constructor` 安装 Hook；它不对 native 机器码、dex method index、内存地址或固定偏移进行 Hook。此前缺乏统一的验证机制，直接以 `getDeclaredMethod("I", Pair.class)`、`getDeclaredMethod("a", int.class)`、`getDeclaredField("a")` 等方式绑定混淆名称，版本更新后可能失效。

新加 `HookSymbols` 运行时符号解析层：

1. **优先精确名称 + 完整 JVM 签名**：参数类型、返回类型、static/instance 必须同时符合预期。
2. **受约束的重命名回退**：只在已确定的 owner 类内部，且仅有一个非 synthetic/bridge 的候选方法与完整签名一致时，按签名回退。
3. **字段符号校验**：同样检查名称、类型、static 属性；只有唯一类型匹配时可回退。
4. **绝不按声明顺序、内存地址、dex method index 或字段偏移选取**。
5. **歧义直接失败**：不得为找到一个“能 Hook 的方法”就选择第一个候选。
6. **按 Hook 独立安装（debug-6 回归修复）**：启动时检测 IDP 与工作区入口仅用于诊断，不再设置任何全局早退门槛。工作区、`OplusLoaderCursor`、旋转缓存与排列模式在独立 `try/catch` 中解析并安装；某个签名改变只禁用对应功能，**不会撤销其他已经可以解析的 Hook**。这样避免单个可选签名变化把可用的 8×4 退回 6×4；但辅助 Hook 缺失仍可能导致重启后图标数据不一致，因此本改动不是持久化修复。

## 关键符号

| 语义 | 归属类（当前归档） | 编译时名称 | 返回 / 参数 | 回退 |
|---|---|---|---|---|
| 模型最终网格边界 | `j5.i` | `I` | instance `Pair(Pair)` | 唯一签名 |
| 横竖屏网格 | `p5.c` | `a` | static `Pair(int)` | 唯一签名 |
| 旋转状态 | `p5.c` | `d` | static `boolean()` | 唯一签名 |
| 原生网格读取 | `com.android.launcher.c6` | `b` | static `int[](Context)` | **不回退**，存在相同签名的 `c(Context)` |
| 紧凑布局初始化 | `LayoutUtilsManager` | `b` | static `void(Context)` | 唯一签名 |
| 紧凑布局状态 | `LayoutUtilsManager` | `c` | static `boolean()` | 唯一签名 |
| 紧凑布局切换 | `LayoutUtilsManager` | `e` | static `void(boolean)` | 唯一签名 |
| 兼容网格迁移 | `OplusUnionGridCompatHelper` | `updateGridIfNeeded` | static `void(Context,int[])` | 仅名称 |
| 工作区位置检查 | `OplusLoaderCursor` | `checkItemPlacement` | instance `boolean(ItemInfo,IntArray)` | 仅名称 |
| 工作区网格设置 | `OplusCellLayout` | `setGridSize` | instance `void(int,int)` | 仅名称 |

有明确业务语义的稳定 API 使用 **仅名称 + 严格签名**；短名称回退需要“唯一签名”且仅对已知 owner 开启。这是故意保守的选择。

## 兼容范围与非目标

- 能适配：在 **类名、方法参数和返回类型仍相同** 时，部分方法被混淆器重新命名；非混淆 API 的方法签名仍匹配时继续 Hook。
- 不能自动适配：owner 类整体换名（如 `j5.i` 改为其他 package/class）、方法原型改变、调用关系或业务逻辑改变。这些变更需要基于 **真实安装 APK** 检查并更新候选 owner 或修改 Hook 机制。
- 不允许以“找到了唯一 `Pair(Pair)` 方法”为依据，在整个 dex 中搜索并 Hook 任意类。那会匹配 unrelated 函数，风险高于不启用模块。
- **6 列截断还没有真机修复结论**：符号解析不是数据库持久化问题的自动修复。 `17.3.9` 反编译归档不能作为 `17.5.5` 或其他版本的行为保证。

## 验收

`HookSymbolsTest` 验证精确名称解析、混淆后唯一签名回退、字段回退、static 和返回值类型一致性、歧义拒绝、继承方法解析、禁止隐式跨类继承回退；另有两个回归测试验证核心工作区签名独立于可选的加载器/旋转签名。

CI 运行 `gradle testDebugUnitTest assembleDebug`。在安装设备上检查 `OLauncherMod` 日志：

- `visual grid symbols verified`：核心工作区签名成功通过预检查；
- `resolved model loader boundary symbol`：最终绑定的真实类及方法名；
- `visual grid symbol probe incomplete; attempting individually resolved hooks`：核心检测不完整，但不会再全局停止，每个 Hook 单独解析；
- `... hook failed`：具体可选功能的独立安装失败，不再连带取消已经可用的工作区 Hook。

即使所有符号解析成功，也仍需检查重启前后数据库 `DELETE/UPDATE` 与实际工作区是否一致，不能把“找到方法”当作“逻辑等价”。
