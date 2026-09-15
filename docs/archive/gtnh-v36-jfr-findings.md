# v36 实际 GTNH / Viewer JFR 性能诊断

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

2026-09-08，GTNH 2.9.0-beta-3，主世界种子 `2777967236474336022`。已安装 Worker 与本地 v36 发布 JAR 的 SHA-256 一致：`2e94b62010b00af03970f8720580a830d46b620edae5d15ac22a15b63f57541a`。Viewer 运行的是 `build/test-v0.2-v36/amidst-gtnh-biomes-v0-2-v36.jar`。

本次结论来自正在运行的整包，而非之前的受控夹具。**主要问题是请求等待放大，加上重复邻域查询和神秘时代结构预测的计算成本；绘制与 GC 不是这次采样中的首要瓶颈。**

## 采样与可复核资料

- 游戏端口测试的 JFR：北京时间 21:33:14 左右开始，60 秒。先在没有 Viewer 的阶段请求 6 张未知区域群系瓦片及对应结构，再在 Viewer 启动后请求 24 张不同区域瓦片。后一组与 Viewer 请求竞争，必须与前一组区分；其末尾少量请求在第一份 JFR 结束后完成。
- 用户拖动地图阶段：游戏 21:35:04–21:35:49、Viewer 21:35:04–21:35:49，各约 45 秒，此阶段未加入额外端口压测。
- 使用 JDK JFR profile，Java 执行采样周期 10 ms；记录超过 10 ms 的线程 park、锁竞争和 socket 等待。
- 原始 JFR、端口逐请求 JSON、火焰图和汇总在 `build/performance-v36/`。火焰图分别为 `game-pan/flamegraph.html`、`viewer-pan/flamegraph.html`、`game-controlled/flamegraph.html`。

火焰图可选择 Worker 查询、Viewer、全部 Java 执行栈、Native 栈和等待栈；支持搜索、点击放大、复位。**Native 样本单独显示**，因为 `Net.accept` 等系统阻塞调用不能被当作 CPU 热点。等待图是累计微秒，各线程时长会重叠；执行样本百分比也不能当作整张瓦片的耗时百分比。

## 1. 计算几十毫秒，用户却等待几百毫秒到数秒

每张群系瓦片覆盖 512×512 方块，128×128 采样，step=4。

| 实际测试 | 数量 | 计算耗时 | Worker 从入队到完成 |
| --- | ---: | ---: | ---: |
| 普通未知区域，首张预热之后 | 5 | 31.94–35.77 ms，均值 33.93 ms | 257.00–376.03 ms，均值 309.39 ms |
| Viewer 同时工作的不同区域 | 24 | 30.65–179.41 ms，均值 48.72 ms | 557.29–4442.32 ms，均值 1235.56 ms |
| 相同坐标重复请求，缓存命中之后 | 5 | 0.425–0.477 ms | 29.76–91.26 ms |
| 普通结构请求，512×512 方块 | 6 | 22.85–47.46 ms | 64.90–118.76 ms |

代表例子：`x=32000,z=32000` 实际计算 **179.41 ms**，分成 25 批，入队到完成 **4442.32 ms**。`computeMillis` 只累计真正执行查询的批次，差值还包含初次排队和后续让出等待。

对应源码：`BiomeWorkerServer.executeQueuedQueries` 固定设置 `nextQueryBatch = now + 50_000_000`，整队列共享 8 ms 预算。到期前的新钩子即使有剩余可用时间也不会执行；多个续算任务、状态查询、结构请求共享队列。完成一个普通瓦片需 4–5 批，因此它的实际吞吐受到服务频率限制。

固定 50 ms 门限还可能与真实游戏 tick 抖动叠加，跳过一次可处理机会。**这点是源码机制上的风险，不是本次直接测得的每 tick 丢失比例。** 这次已经直接测得的是：等待远大于计算，而且缓存命中也有显著排队延迟。

## 2. Worker 的热点已转移到缓存与邻域构造

拖图阶段共有 1750 个 Java 执行样本，其中 **614 个包含 Worker 查询调用链**，没有截断栈。以下“最近 Worker 调用点”互不重叠，百分比以这 614 个样本为分母：

| 最近 Worker 调用点 | 样本 | 占比 |
| --- | ---: | ---: |
| `getRawBiome:647`，`rawBiomeCache.get` | 192 | 31.3% |
| `createBlendChunk:414`，反射读取 `biomeID` | 59 | 9.6% |
| `createBlendChunk:420`，`TreeMap.put` | 39 | 6.4% |
| `RwgChunkBiomeReplay.replay:102`，原生地表回调 | 55 | 9.0% |
| `RwgTerrainAccess.terrain:48` | 47 | 7.7% |
| `RwgTerrainAccess.calculateRiver:42` | 26 | 4.2% |

最顶层的执行样本也集中于 `HashMap.getNode`、`LinkedHashMap.afterNodeAccess`、`CellNoise.border` / `noise`。行号由 JFR 记录，JIT 内联的归属存在统计误差，但缓存路径在调用链上非常清晰。

源码每区块扫描 21×21 个 RWG 网格候选，即 **441 次缓存查询**。一张 32×32 区块瓦片会发起约 **451,584 次**这种扫描查询（按每区块创建一次计算，精算权重展开还可能增加调用）。相邻区块共享很多网格点，即便已有缓存命中，仍会反复创建包装键、查表和调整 LRU 顺序。

当前 `coordinateKey` **已经做过 64 位混合**，不是可以再靠修一次简单 `x ^ z` 哈希解决的问题。后续应减少重复查询次数，例如邻域窗口平移复用、批量网格取样，以及减少装箱 / 临时集合。生成公式和累加顺序仍应保持不变。

## 3. 神秘时代结构预测占据大量查询计算

拖图阶段，**190 / 614 = 30.9%** 的 Worker 样本经过：

```text
BiomeWorkerServer.sampleStructures:848
  → ThaumcraftStructurePredictor.predict:106
    → consumeVegetation:181
      → SurfaceBiomeSampler.getBiomeAt
```

其中 184 个样本经过 `consumeVegetation:181`，它逐区块查询群系以推进神秘时代植被生成的随机序列。这些样本与上一节的缓存 / 地表样本是包含关系，**不能把两节百分比相加**。

普通 `structures` 请求当前会连带执行这一组预测，没有按当前需要的结构类型过滤。它也没有像主世界 `biomes` 那样续算；端口实测的单批结构计算 22.85–47.46 ms，明显超过名义 8 ms 软预算。它既增加地图完成时间，也会阻塞其他查询。

可优化方向：按所需图层发送结构类型集合；复用群系查询结果与逐区块结构结果；将结构计算纳入同一套可续算调度。不能简单删掉随机数消耗，否则会损失节点 / 祭坛预测准确性。

## 4. 首次瓦片要等结构图层结束才显示

这是源码核查结果，而非火焰图能直接计时的事件：

```text
FragmentQueueProcessor.Load.run
  → LayerManager.loadAll → LayerLoader.loadAll（顺序加载所有启用图层）
  → 全部完成后提交 completion
  → FragmentCache.completeLoad → setLoadedDimension
  → Fragment.hasDisplayData 才允许首次显示
```

已有瓦片刷新可以保留旧图，但首次瓦片的 `loadedDimension` 为空。因此，即使准确群系底图已完成，也会等待后续结构图层。应把“准确底图完成即可发布”与“结构图标继续加载”拆开，同时保留维度身份校验和不可变图像发布。

这项修改无需降低群系精度，也不要求先画低精度预览；它减少的是准确结果已经可用后的显示等待。

## Viewer 和 GC 的对照

Viewer 45 秒内共 162 个 Java 执行样本：FragmentLoaderExecutor 81、重绘请求线程 44、AWT 事件线程 22。单个 Fragment-Worker 线程记录到累计约 5–7 秒的 socket 等待；很多工作线程在等 Worker 回复。

游戏 GC pause 合计 **69.64 ms**，最大 **21.18 ms**；Viewer 合计 **16.40 ms**，最大 **4.10 ms**。这不能排除其他场景的 GC 问题，但不足以解释这次每张瓦片数百毫秒乃至数秒的迟延。样本更支持优先处理 Worker 与请求完成流程，而非优先更换渲染后端。

## 建议的修改顺序与验收

1. 以真正的服务 tick / 共享截止时间管理预算，避免固定间隔造成额外空转；结合游戏 tick 剩余时间分配预算，保护游戏响应。先改善等待放大。
2. 准确底图完成后立即发布，结构图标随后更新。分别记录“底图可见”和“全部图层完成”的延迟。
3. 结构按需查询、结果缓存、分批推进，重点处理神秘时代。
4. RWG 邻域窗口复用，减少 441 次 / 区块的重复查表，再考虑更细粒度噪声优化。

后续 A/B 应固定种子、坐标、缩放、图层和缓存状态，同时比较计算时间、端到端时间、最大单批占用及游戏 tick 延迟。不能仅增加预算后用瓦片变快宣称解决，因为那可能再次造成游戏卡顿。

## 复现工具

新增 `gtnh-worker/tools/Capture-Jfr.ps1` 与 `JfrFlameReport.java`，前者启动有自动截止时间的录制，后者使用 JDK 21 的流式 JFR reader 生成离线 HTML 与 JSON。无需安装火焰图第三方库，也无需导出数百 MB 的完整事件 JSON。

```powershell
powershell -File gtnh-worker/tools/Capture-Jfr.ps1 -TargetProcessId 8504 -JdkBin 'C:/Program Files/Zulu/zulu-21/bin' -OutputDirectory build/performance-next -Seconds 45
# PID 必须重新确认，不要盲用旧编号；录制结束后：
java gtnh-worker/tools/JfrFlameReport.java <录制文件.jfr> build/performance-next/report
```

普通瓦片端口统计仍可使用 `Profile-WorkerTiles.ps1`。本次各录制均已自动结束；原始文件可以进一步用 JDK Mission Control 检查。
