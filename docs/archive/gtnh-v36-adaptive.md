# v36：按群系依赖选择精算，按时间预算续算

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

## 解决什么

v35 对每个未知区块都计算 256 列混合高度，构造 65,536 个临时方块，执行地图生成和全部地表回调。地图通常每区块只显示 16 个采样点，许多地表回调只改方块，执行这些步骤并不会改变地图群系。同时，Worker 曾在游戏线程连续跑完整请求并清空队列，多个瓦片会连续占用游戏线程。

v36 保留精确的 RWG 群系混合、选择噪声、河流条件和 BOP 注册对象；仅在地表回调可能访问最终群系数组时回放完整区块。不是根据温度阈值或距离边界猜测，也不使用低精度预览替换精确结果。

## 选择流程

1. 获取该区块参与混合的实际 realistic biome 对象。
2. 如果所有候选对象的地表回调均不访问最终群系数组，直接按实际请求像素计算混合群系与河流。
3. 候选中有敏感对象时，进一步检查 256 列实际选中的对象。只要任意一列可能访问群系数组，整区块精算。即使那列不是地图采样点，也不能跳过。
4. 精算沿用 v35：完整混合高度、临时方块、原生 mapGen、河流、所有 rReplace/BOP painters 与基岩随机数消耗，保持顺序。
5. 已加载区块仍优先读取游戏内存中的最终群系；缓存仅保存纯预测。

因此，普通温湿度交界仍使用原来的精确混合公式；会发生地形驱动群系替换的交界处精算。特殊回调即使位于大片同种群系内部，也精算。算法中没有写死沙漠、BOP 群系 ID 或经验边界宽度。

## 如何判定回调

`RwgRuntimeClasses` 在 Worker preInit 安装只读字节码观察器。Worker 声明 `before:RWG`，尽量在 RWG 初始化地表前安装。只有观察器处于 LaunchWrapper 转换链末尾时，才保存当前类的最终字节码；已经提前加载的类、未知类加载器或不可用的字节码均回退到原生回放。不会重新执行 Mixin/ASM 转换，也不会用原始 JAR 的方法体冒充运行时实现。

`RwgSurfaceEffects` 检查最终 `BiomeGenBase[]` 参数：

- 完全不读取该参数的回调可走快速路径。
- 直接将该参数传给其他回调时，解析实际对象的字段 / 数组接收者，并递归检查所有可能的地表对象。BOP 的 `surfaces[]` 按实际对象检查。
- 读取、写入、别名、逃逸、未知接收者、非支持的调用方式、对象字段写入及递归超限都会回退。
- 判定按注册对象缓存，随 sampler / 世界上下文重建。运行期间修改已注册地表对象或使用热替换工具后，应重新创建世界上下文或重启；不是通用 Java 副作用证明器。

这项分析针对 RWG 隔离预测流程的最终群系数组。与 v35 一样，不保证任意模组全局副作用、Forge ReplaceBiomeBlocks 事件监听器或人口生成后处理均可预测。未知第三方回调以完整回放为默认；既有精度范围见 [实际 GTNH 生成链](gtnh-biome-accuracy.md)。

## 避免持续占用游戏线程

主世界 `biomes` 请求保存当前像素索引、已完成结果和纯预测缓存，按默认 **每 50 ms 最多约 8 ms** 的软预算推进。让出后排到队尾，短请求可以通过；新一批从上次位置继续，整张结果完成后才返回 Viewer。

Forge END 与 ESC 暂停钩子共享预算，不能在同一时间窗口重复获得预算。查询超时 / 中断会取消后续续算；已经完成的纯预测可被后续请求复用。切换世界或生成器时，中止跨世界的在途结果。

注意：原生单个区块回调无法安全抢占，首次类解析、一个特别慢的回调可能超过 8 ms。这不是硬实时上限。结构预测、其他维度及 `compare_biomes` 诊断暂未改成像素续算，单独的长请求仍可能超预算；新 profile 字段可帮助识别这些情况。精算很多的区域仍有实际计算成本，不能承诺处处达到 v32 的速度。

## 测试与性能证据

冻结 v35 developer JAR 与新源码使用相同受控噪声和回调，各查询 4 张瓦片，每张 16,384 个像素，交替执行 5 轮取中位数。校验最终群系序列一致。

| 受控输入 | v35 / 4 张 | v36 / 4 张 | 吞吐比 |
| --- | ---: | ---: | ---: |
| 普通单一条目 | 245.295 ms | 50.882 ms | 4.821× |
| 普通混合边界 | 315.644 ms | 88.959 ms | 3.548× |
| 密集敏感地表混合 | 330.763 ms | 345.172 ms | 0.958× |

最后一项未提速，约慢 4.4%，公开保留该结果；该场景主要依赖续算改善游戏响应。这些是计算吞吐测试，**不含 50 ms 调度等待，不是实际整包每张瓦片耗时或 FPS 承诺**。

实际本地 `RWG-alpha-1.5.2.jar`（SHA-256 `9be5c54b56cf2a08d507a5568dec1537ca6f5e958a2ce9d39e32207870a6bd6f`）的地表方法体覆盖测试：20 个实现中 19 个可走快速路径，1 个需精算。这个数量不是地图面积比例，运行时转换后的可用比例还应以游戏日志为准。测试仅适配输入类型，不把第三方 JAR 打包发布。

回归覆盖：实际 JAR 的混合/高度和 native callback 对照；BOP 数组委托；未知字节码回退；数组读写与逃逸；随机与跨列群系修改；负坐标和混合边界；续算不重算像素；真实 TCP 长瓦片让出给短请求；暂停钩子。最终构建测试数量及源码 / JAR 摘要见测试包 `build-info.json`。

## 调试和维护

更新 Worker 后必须重启 GTNH；仅重新进入存档无法加载新类。Viewer 默认 GTNH、原生控制台、等待 Worker、旧图保留、瓦片缓存等行为继续保留。

游戏日志会逐对象记录 `RWG adaptive biome-only` 或 `RWG adaptive native replay`、realisticId 和回退原因。若都是 `final runtime bytecode unavailable`，检查其他 coremod 的加载顺序；不要通过强行信任原始 JAR 来规避该保护。

在游戏运行、Viewer 同样图层条件下执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\Profile-WorkerTiles.ps1 -X 32000 -Z 32000 -Tiles 4
```

报告中 `computeMillis` 是累计执行时间，`elapsedMillis` 含排队及让出等待，`maxSliceMillis` 是一次续算的最大占用，`slices` 为批次数；`predictionStats` 是当前 sampler 累计选择的快速 / 精算区块数（不是当前瓦片独占计数）。每次测首次生成请换一片未预测区域；重复相同坐标测的是缓存。

保留的实际群系对照工具：

```powershell
powershell -ExecutionPolicy Bypass -File .\Compare-WorkerBiomes.ps1 -X -240 -Z -240 -Width 64 -Height 64
```

可在 **游戏 JVM 参数** 加 `-Dgtnh.amidst.worker.fullReplay=true`，强制全回放，用相同坐标作人工 A/B 对照。默认 false。`-Dgtnh.amidst.worker.tickBudgetMillis=8` 可调预算，限制 1–20 ms。两项均在启动时读取。

| 修改内容 | 源码入口 |
| --- | --- |
| 运行时字节码观察、回调依赖判定 | `RwgRuntimeClasses.java`, `RwgSurfaceEffects.java` |
| 快速 / 精算路由、混合及河流 | `SurfaceBiomeSampler.java` |
| 原生区块回放 | `RwgChunkBiomeReplay.java` |
| 可续算像素游标、缓存 | `OverworldTileCache.java` |
| 时间预算、取消、请求 profile | `BiomeWorkerServer.java` |
| 实际 JAR 覆盖、精度与吞吐 | `InstalledRwgEffectsTest.java`, `RwgSurfaceEffectsTest.java`, `RwgReplayBenchmark.java` |

构建：`gradlew.bat assembleRelease -PrwgReferenceJar=build/biome-accuracy-reference/RWG-alpha-1.5.2.jar --offline --console=plain`。
基准：Worker Gradle `benchmarkReplay -PbaselineWorkerJar=<冻结 v35 developer JAR>`，结果在 `gtnh-worker/build/reports/performance/replay-v36.json`。developer JAR 仅作测试基线，不能装入游戏。
