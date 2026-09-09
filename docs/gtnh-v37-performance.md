# v37：按真实采样结果修复地图生成性能

## 修复依据

参见 [v36 的 JFR 实测](gtnh-v36-jfr-findings.md)。普通新瓦片计算均值约 33.93 ms，但 Worker 内总耗时约 309 ms；Viewer 并发时总耗时均值约 1236 ms。主要损耗包含重复 RWG 网格查表、反射、结构预测，以及计算之外的排队和分批等待。GC 和 Viewer 绘图没有成为该次采样的主要瓶颈。

## 改动与精度

1. `RwgBiomeGrid` 将 8 方块间距的原始 RWG 网格分为 16×16 单元的页，保留最近页直接访问，减少逐点 LRU 操作。每个单元同时缓存真实群系对象和 realistic ID，反射读取只发生在首次取样。默认最多 256 页，每个 sampler 独立，不跨世界/种子复用。
2. 混合候选集用 256 槽数组替代每区块的 `TreeMap`。仍按 ID 升序处理，重复 ID 仍取最后一个对象；不改变权重求和、插值、选择噪声、河流或地表回放的顺序。
3. `WorkerTickBudget` 用当前 tick 已花时间计算预测预算：`min(配置上限, max(1 ms, 45 ms - 当前 tick 耗时))`，默认上限 20 ms。去掉从上一次查询开始计 50 ms 的门限，避免游戏 tick 稍早到达就整轮跳过。每个可续算请求约 4 ms 后重排队尾，同一 tick 内仍可使用剩余预算。Forge END 和 ESC 返回钩子不会重复领取预算。
4. 神秘时代节点/祭坛预测保留逐区块的 Forge 随机种子与原始内部随机数调用顺序，只在区块之间让出。普通主世界结构按三个预测器阶段续算。每个在途请求保存自己的结果和游标，切换 sampler 后拒绝继续旧世界的结果。
5. 主世界结构增加可选 `structureGroup=standard/thaumcraft` 请求，Viewer 为两组分别缓存。普通村庄/地牢不再隐式触发神秘时代的逐区块预测；只有开启节点或祭坛图层才请求后一组。字段省略仍返回原来的完整集合，协议保持 18。原版刷怪笼查询继续使用单独通道。
6. Viewer 完成准确底图后立即发布这一图层，后续结构仍可加载。图层发布标记与坐标/维度一起重置；未完成的新图层不会显示复用对象中遗留的旧图标。沿用 v35 完整图片原子替换和刷新期间保留旧图的处理。

精度范围仍遵循 [实际 GTNH 调用链](gtnh-biome-accuracy.md) 和 [v36 回调依赖判定](gtnh-v36-adaptive.md)。这里没有降低采样分辨率、简化边界或取消敏感地表回放。节点/祭坛仍是既有规则下的可能位置，不新增精确结构保证。

## 验证方法

冻结修改前的 v36 developer JAR，对照当前构建。两边均启用相同的回调依赖判定；不能让旧版本意外走全回放而夸大加速。受控 RWG 形状输入，每项 4 个 128×128、step=4 瓦片，预热后交错执行 5 轮，中位数和最终群系序列校验和写入 `replay-v37.json`。这些是计算吞吐测试，不包含真实整包 TPS、客户端 FPS 和 TCP 排队。

回归包含原生成链逐点对照、真实 RWG JAR 方法体检查、负坐标/页边界/缓存淘汰、敏感回调完整回放、神秘时代多任务交错续算的随机流与顺序、忙碌 tick 时间预算、Forge/ESC 去重、TCP 分组字段与旧请求兼容，以及底图完成而图标仍阻塞时的可见性。

最终构建验证：Viewer 119 项通过、10 项开发工具测试跳过；Worker 47 项通过。安装用 Worker 的 SRG 映射检查通过，开发用 JAR 被正确拒绝。

| 受控区域 | v36 四瓦片计算中位数 | v37 四瓦片计算中位数 | 吞吐比 |
| --- | ---: | ---: | ---: |
| 普通区域 | 57.810 ms | 26.098 ms | 2.215× |
| 混合边界 | 102.669 ms | 54.682 ms | 1.878× |
| 敏感地表密集区域 | 313.435 ms | 265.215 ms | 1.182× |

## 复测与限制

关闭 GTNH 后替换旧 Worker 为 `amidst-gtnh-worker-v37.jar`，再重启游戏，并使用配套 v37 Viewer。仅退出存档不能替换已加载的模组。此次构建未覆盖运行中的游戏文件；真实游戏中的 v37 延迟和 FPS 尚待安装后采样。

默认预算上限可通过游戏 JVM 参数 `-Dgtnh.amidst.worker.tickBudgetMillis=8` 等调整（1–20 ms，启动时读取）。`-Dgtnh.amidst.worker.fullReplay=true` 保留用于精度 A/B 对照。未暂停时会根据当前 tick 的耗时缩减预算；ESC 暂停时没有正常的世界 tick，使用配置上限。

以上是软预算。一个原生地表区块回调、类初始化或单个结构预测器阶段仍不能安全抢占；其他维度、刷怪笼、精度诊断等尚未全面分片。因此不能承诺所有模组组合、所有区域完全无卡顿。全部打开结构图层时仍需支付其计算成本，分组主要消除未请求的计算和底图等待。

测试包内运行 `Profile-WorkerTiles.ps1` 可采集 `computeMillis`（实际计算）、`elapsedMillis`（含排队/续算）、`maxSliceMillis`、`slices`。后两项描述单请求片段，不是整 tick 的预测耗时。需要整包帧时间时，用 `Capture-Jfr.ps1` 和 `JfrFlameReport.java` 继续采样；命令见 v36 JFR 报告。

## 维护入口

| 要调整的行为 | 源码位置 |
| --- | --- |
| 分页网格、原始 ID 复用 | `gtnh-worker/src/main/java/amidst/gtnh/worker/RwgBiomeGrid.java` |
| 候选群系、权重与精算路由 | 同目录 `SurfaceBiomeSampler.java` |
| tick 剩余时间 | 同目录 `WorkerTickBudget.java` |
| 队列、4 ms 请求片段、结构分组 | 同目录 `BiomeWorkerServer.java` |
| 神秘时代区块游标与随机顺序 | 同目录 `ThaumcraftStructurePredictor.java` |
| 分组缓存、图层到请求的映射 | `src/main/java/amidst/gtnh/structure/GtnhRoguelikeDungeonProducers.java` |
| 底图提前发布、旧图层隔离 | `src/main/java/amidst/fragment/Fragment.java` 与 `gui/main/viewer/Drawer.java` |

完整构建：`gradlew.bat assembleRelease -PrwgReferenceJar=build/biome-accuracy-reference/RWG-alpha-1.5.2.jar --offline --console=plain`。

基准：Worker Gradle `benchmarkReplay -PbaselineWorkerJar=<冻结 v36 developer JAR>`，输出 `gtnh-worker/build/reports/performance/replay-v37.json`。developer JAR 只供基准，不能安装进游戏。测试包 `build-info.json` 和 `source-sha256.txt` 记录源码、JAR 摘要及测试计数，便于检查本地修改与构建的一致性。
