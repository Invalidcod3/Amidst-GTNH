# Worker 启动、崩溃与连接排查

地图刷新出现扫过的黑块、v34 回放过慢时，见 [v35 修复与性能报告](gtnh-v35-performance.md)。

主世界群系边界、BOP/RWG 地表覆盖和精确坐标读数的排查，见
[v34 通用 GTNH 群系回放与准确性](gtnh-biome-accuracy.md)。

## v32：单人存档按 ESC 后预测停止

Minecraft 1.7.10 的内置服务器在暂停时仍循环调用 `IntegratedServer.tick()`，
但该方法跳过 `super.tick()`。Forge 的 `ServerTickEvent` 恰好在被跳过的
`MinecraftServer.tick()` 中派发。旧 Worker 只在这个事件中处理存档内查询，
因此 TCP 仍监听，但查询一直排队，最终超时。

v32 通过一个小型 Forge coremod，在 `IntegratedServer.tick()V` 的每个正常
返回处加入 `WorkerTickHooks.onIntegratedServerTickEnd()`。运行时方法名为
`func_71217_p`，开发环境为 `tick`。该调用只排空 Worker 查询队列，保留原有
暂停、保存、视距更新及世界 tick 逻辑；不能为修复此问题调用 `super.tick()`
或更改暂停标志，也不能把单人存档的查询移到客户端或网络线程。

正常运行时保留 Forge END 事件作为处理入口，专用服务器也继续使用它。
内置服务器未暂停时可能先在事件中排空队列，再运行注入入口；任务取出即从
队列移除，因此不会重复计算同一个查询。主菜单和远程联机的客户端入口不变。

维护入口：

- `core/WorkerLoadingPlugin.java`：早期启动注册，只返回类名，不加载游戏类；
  排序 1001 位于 FML 反混淆之后。无需新增 Mixin 依赖。
- `core/IntegratedServerTickTransformer.java`：仅转换内置服务器类和上述
  `()V` 方法，在 `RETURN` 前调用静态入口；重复应用不会增加重复调用。
  不重新计算栈帧，避免为求类型层级提前加载 Minecraft。若其他模组把目标
  方法改成无法识别的形态，保留原始字节码并记录错误，普通 tick 入口仍保留。
- `WorkerTickHooks.java`：只读当前服务引用，执行现有的同步请求队列；不引用
  客户端专属类型。引用由模组启动、禁用和专用服务端停止生命周期管理。
- `build.gradle.kts`：开发和运行时 JAR 均写入 `FMLCorePlugin` 与
  `FMLCorePluginContainsFMLMod=true`，后者确保 Forge 也发现普通 `@Mod`。
  发布校验器检查这两个属性以及三个新增类，避免只打包普通模组入口。
  只配置 `jar` / `reobfJar`，不要用 `withType<Jar>` 把模组启动元数据写进
  RFG 生成的 Minecraft 或 launcher 辅助归档。

这次必须替换 `mods` 中的 Worker 并重启 GTNH：字节码补丁在游戏启动时加载，
无法通过重新进入存档或只更换 Viewer 生效。协议仍是 18，v31 Viewer 可继续用。
游戏启动日志应包含 `Installed paused-world Worker hook`；若出现
`Cannot install paused-world Worker hook`，请保留完整异常和模组版本列表。

回归测试包括实际 Forge 1.7.10 类的字节码检查、MCP/SRG 方法名、多返回路径、
重复转换、未知方法形态、专用服务端不转换，以及执行转换后的模拟服务器循环。
后者通过真实 TCP 请求核对暂停 → 恢复 → 暂停时查询仍完成、执行线程不变、
暂停时世界 tick 计数不增长。它不启动完整 GTNH，仍需游戏内确认其他模组的组合。

游戏内重点验证：进入单人存档后按 ESC，保持暂停并在 Viewer 拖向未预测区域；
瓦片应继续生成。恢复游戏再暂停，重复拖动；再测试切出游戏触发的自动暂停。
可用 `profile-viewer.bat` 观察请求是否持续返回。保存或加载期间本身的服务器
阻塞仍会推迟查询；这个修复不能使卡住的服务器线程继续工作。

## 2026-09-08：v25 本地构建进入存档崩溃

报告中的首个异常是：

```text
NoSuchFieldError: Class net.minecraft.world.World does not have member field 'boolean isRemote'
at AmidstGtnhBiomeWorkerMod.recordChunkStateChange(...)
```

原因在发布脚本。RetroFuturaGradle 的 `jar` 任务生成开发用的 MCP 名称
JAR，`reobfJar` 生成另一个使用 SRG 运行时名称的 JAR。旧脚本依赖了
`reobfJar`，但 `from(...)` 仍引用 `jar.archiveFile`，随后把开发版改名为
可安装文件。因此“重混淆任务成功”和“文件名没有 -dev”都不能证明发布包正确。

出问题的 Worker SHA-256 与开发 JAR 完全相同：

```text
88efe249786a5dd4bffccda01fc48889185f9546be817af073598ae17c3c3272
```

修复后的 `gtnh-worker/build.gradle.kts` 明确使用 `reobfJar.archiveFile`，
并通过 `tools/VerifyWorkerJar.java` 检查最终归档及开发归档的区别。
Java 源码仍使用 `world.isRemote` 等可读名称，不应手动改成 `field_72995_K`。

## 代码入口与职责

| 文件 | 修改或排查位置 |
| --- | --- |
| `gtnh-worker/build.gradle.kts` | 运行时 JAR 的选择、复制、发布校验与测试 |
| `gtnh-worker/tools/VerifyWorkerJar.java` | 无需启动游戏的字节码映射检查 |
| `AmidstGtnhBiomeWorkerMod.java` | Forge 生命周期、tick、区块事件和服务发布 |
| `core/IntegratedServerTickTransformer.java`、`WorkerTickHooks.java` | 内置服务器暂停时的查询入口；见上面的 v32 说明 |
| `BiomeWorkerServer.java` | IPv4 监听、请求排队、游戏线程执行及错误响应 |
| `WorkerConfig.java` | `enabled`、端口和共享口令；JVM 参数覆盖配置文件 |
| Viewer 的 `GtnhBiomeWorkerClient.java` | TCP 连接、响应检查、断线恢复和超时诊断 |
| Viewer 的 `Amidst.java`、`logging/WindowsConsoleLauncher.java` | 双击时打开原生 Windows 控制台、保留 JVM/应用参数及默认日志路径 |
| Viewer 的 `logging/FileLogger.java` | UTF-8 日志追加、退出时刷新待写日志 |

网络线程不能直接查询游戏世界；请求通过 `FutureTask` 队列交给 tick
线程执行。单机存档存在时由服务端 tick 执行；主菜单或连接远程服务器时，
本机没有 `WorldServer`，使用客户端 tick。后者依赖本机安装的模组和配置，
无法读取远程服务器的权威区块数据。

## 构建与检查

在仓库根目录使用 JDK 21，并安装可被 Gradle 发现的 JDK 25：

```powershell
.\gradlew.bat assembleRelease
```

单独检查 Worker：

```powershell
cd gtnh-worker
.\gradlew.bat stageReleaseJar -Pgtnh.modules.codeStyle=false
```

`stageReleaseJar` 执行 Worker 网络测试和归档检查，任一步失败都应停止交付。
校验器的独立用法见 `gtnh-worker/tools/README.md`。根目录启动脚本从
`metadata.properties` 读取文件名，避免启动旧 JAR。

Worker 测试使用真实 IPv4 TCP 连接，在偏好 IPv6 的测试 JVM 中手动驱动
游戏请求队列；不需要真实存档。Viewer 测试覆盖断线恢复、可达服务的响应超时
和 Worker 错误透传。完整整合包中的世界加载和群系算法仍需游戏内验证。

从 v28 起，Viewer 测试还覆盖先启动 Viewer 再启动 Worker，以及监听已开放
但握手超时、游戏线程队列超时、握手提前断开后的自动恢复。测试通过真实 TCP
构造这些状态，并执行实际 `GtnhMinecraftInterface` 初始化。协议和口令错误
必须终止等待并报告；不能把所有异常都改成重试。

## Viewer 日志和启动等待

Windows 双击 GTNH Viewer JAR（`javaw.exe`）时，会通过系统自带的 Windows
PowerShell 打开原生控制台，再使用同一个 Java 安装目录的 `java.exe` 运行
原 JAR。JVM 参数、应用参数和工作目录均保留；原始 `javaw` 进程随后退出。
控制台同时显示标准输出和标准错误；PowerShell 的 `-NoExit` 让程序退出后仍保留
控制台，便于查看最后的异常，查看完后手动关闭控制台窗口。
显式使用 `java.exe`、已有控制台、原版模式或 IDE 类目录启动时不会再次打开控制台。

启动代码集中在 `WindowsConsoleLauncher.java`，不改 JAR 文件关联或系统设置。
PowerShell 使用单引号字面量保存路径和 Windows 命令行参数；启动脚本用 UTF-16LE
Base64 传递。不要改成把种子、口令等用户参数直接拼进 `cmd /c` 字符串。回归测试
通过真实 PowerShell/Java 子进程核对空参数、中文、空格、引号、反斜杠及 shell 字符。
Base64 仅用于传输，不是加密；不要把启动命令（可能含口令）复制到诊断日志。

若系统限制 PowerShell 导致自动控制台启动失败，使用 BAT 或 `java -jar` 启动。
应用日志追加到
JAR 旁的 `viewer.log`；该位置不可写时改用用户目录的 `amidst-viewer.log`。
可用 `-log` 指定路径，实际路径会写在启动日志中。

等待期间可在控制台按 `Ctrl+C` 结束 Viewer。正常退出会刷新待写文件日志。
`Help > Display Log Messages ...` 仍可查看原有日志快照。

- `Waiting for GTNH worker ...`：尚未完成握手，会一直等待；连接失败后间隔
  2 秒重试。单次网络读写另有 30 秒超时，长时间等待每约 30 秒写一次进度。
- `Still waiting ...`：最近一次等待原因，包括端口未监听、握手超时或游戏
  线程尚未处理握手。启动 GTNH 到主菜单后应自动恢复，无需确认弹窗。
- `available again; resuming requests`：握手成功，继续初始化或恢复中断的请求。

Viewer 不会修改 Worker 配置或自动启动游戏。若 Worker 的已有配置为
`enabled=false`，需要在游戏启动前改为 `true`；仅保持 Viewer 等待不会启用它。

## 游戏日志中的关键线索

从 v27 起，双击 Viewer JAR 默认连接 GTNH Worker；命令行 `-vanilla`
才进入原版 Amidst 的 profile 流程。Worker 首次生成配置即为 `enabled=true`，
无需为生成配置反复启动游戏。已有配置的 `false` 和 JVM 覆盖仍按用户设置执行。

客户端查看实例的 `logs/fml-client-latest.log`，专用服务端查看对应 FML
服务端日志。搜索 `amidstgtnhworker`，优先保留首个异常的完整堆栈。

- `listening on 127.0.0.1:47117 (protocol 18, ...)`：服务已绑定实际 IPv4
  地址；日志不输出口令。后续的 `loaded from ...` 指明正在使用哪个 JAR。
- `Cannot bind ...`：检查另一个 GTNH 进程是否占用该端口，以及
  `config/amidstgtnhworker.cfg` 中的 `worker.port`。监听失败会关闭 Worker，
  Minecraft 继续运行；修正配置或端口冲突后重新启动实例。
- `GTNH biome worker is disabled`：检查 `worker.enabled` 及其 JVM 覆盖参数。
- `Connected to GTNH worker ... timed out`：TCP 已连接，但请求没有及时完成。
  检查游戏是否仍在加载、tick 是否卡住，以及 Worker 的首个查询异常。
  若仅在 ESC 暂停时发生，确认已安装 v32 Worker 且暂停入口成功加载。
  普通地图查询的响应超时会直接报错；启动/恢复用的 `hello` 握手超时会继续
  等待游戏完成加载。不要将查询中的实际运行时错误当成启动等待。
- `GTNH worker command ... failed`：日志含命令、种子、维度、查询范围与异常
  堆栈；Viewer 同时收到异常类型和消息。`NoSuchFieldError` /
  `NoSuchMethodError` 应先排查归档映射及模组版本。
- `disabled after a Minecraft linkage failure`：区块事件发现运行时不兼容，
  Worker 被关闭以避免导致世界加载崩溃。应更换正确归档后重启。
- `unsupported protocol` / `authentication failed`：分别检查两个 JAR 是否配套，
  或配置口令是否与 Viewer 参数一致。

Windows 可以检查端口：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 47117
Test-NetConnection 127.0.0.1 -Port 47117
```

TCP 检查成功只说明监听可达，不代表游戏线程能成功完成握手和群系采样。
仅有“端口不可用”的提示不足以判断是不是端口冲突。本次提供的游戏日志记录了
监听启动，但未找到对应的握手异常，不能据此断定用户的连接失败一定由 IPv6 引起。

## 游戏内回归步骤

1. 关闭实例，将旧 Worker 移出 `mods`，只安装测试包中配套的新版 Worker。
2. 先双击 Viewer，确认日志显示等待，再启动 GTNH 到主菜单，确认自动连接；
   核对游戏日志中的 JAR 路径、IPv4 地址和协议号。
3. 加载测试存档、退出到主菜单、再加载另一个存档，检查有无新的崩溃报告。
4. 保持 Viewer 打开，检查上述切换以及主世界区块加载后的群系刷新。
5. 连接远程服务器，检查本机 Worker 不因客户端区块事件崩溃。
6. 若仍失败，提供 Worker 启动日志、首个异常堆栈、Viewer 日志、JAR 校验值，
   以及当时是在主菜单、单机存档还是远程服务器。不要分享共享口令。
