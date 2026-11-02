# 出生点预测与维护

## 当前调用链

1. `BiomeWorkerServer.sampleSpawn` 在游戏线程查询；只支持主世界。
2. `SavedWorldSpawn` 优先读取种子与生成器均匹配的已加载世界出生点，返回 `RECORDED` 和 X/Y/Z，不生成区块。
3. 否则由 `SpawnSearchCache` 保留最多四个独立种子/提供器/生成器/噪声模式任务。`SpawnSearch.Job` 保留随机数和试探进度，超时后可续算。
4. RWG2 的 `Rwg2SurfaceBiomeSampler` 重放地形、河道、地表、洞穴、岩浆室和熔岩洞室，缓存紧凑表面摘要。RWG-Alpha 的 `SurfaceBiomeSamplers` 创建无存档的 RWG 预览世界。`RwgChunkBiomeReplay` 重放完整地形和地表替换，再由 `RwgSpawnCaves` 按配置执行原生洞穴生成。
5. `SpawnSearch.Rules` 按原版或 BOP 提供器规则判断地表，返回 `ESTIMATED`。未知提供器返回 `UNAVAILABLE`；未完成结果不使用 `(0,0)` 代替。
6. Viewer 的 `GtnhSpawnOracle` 在后台刷新快照；`SpawnProducer` 和 `GtnhSpawnIcon` 读取同一结果。`WorldIconSelection` 跟随当前出生点，避免信息框保留旧坐标。

自 v0.4.10 起，RWG2 任意种子也返回估算出生点。此前该分支直接返回 `UNAVAILABLE`，造成无标记／无法跳转。原生 RWG2 的 `findBiomePosition` 返回 null 且不消耗随机数，从 `(0,0)` 开始原版游走。Viewer 首次获得结果或记录改变时只刷新旧／新位置附近瓦片，避免取消整屏采样。

## 必须保留的语义

- 原版规则和 BOP 规则不同，不能用群系默认 `topBlock` 或“看起来有草”代替实际表面方块。
- 读取表面从 Y=63 向上扫描连续方块，不能改为从最高方块向下搜索。
- 出生点随机数只用于初始群系搜索和 X/Z 游走；洞穴拥有自己的随机数。搜索第 1000 次移动后立即停止，不额外测试终点。
- 地形及洞穴只操作预览数组；不能在真实世界上调用生成过程来验证预测。
- 普通群系绘图不会执行洞穴，也不能把洞穴前的表面写入出生点缓存。失败后重试完整区块，完成后才缓存。
- 缓存和任务由游戏线程持有；生成配置改变需重启游戏。同种子不代表同存档，已加载世界的记录每次优先读取，不能被估算缓存盖住。
- `CoordinatesInWorld.getY()` 是地图 Z；记录中的 Minecraft 高度 Y 为独立字段。未知估算高度不编造地面高度。

## 精度边界和证据

独立估算尚未重放第三方 `ReplaceBiomeBlocks` 事件、结构和区块地物覆盖。它们可能改变表面；
目前没有跨种子的误差统计，不能给出可靠的准确率或固定误差半径。

报告种子 `-8138049151491905853` 的 `level.dat` 确认出生点为 `(-58,64,-180)`，
该处 Y=63–65 为石头，Y=66–67 为空气。随机游走第 71 次到达该处，旧估算在第 201 次停于 `(-336,-573)`。
原版洞穴几何在该列仅覆盖 Y=25–27 和 35–37，因此无法解释该处地表差异；替换洞穴的模组不在此排除结论内。
未用当前完整 Worker 重放该种子的首次建档，不能宣称该偏差已修复。

## 回归入口

`SpawnSearchTest` 检查提供器规则、随机顺序及上限；`SpawnSearchCacheTest` 检查续算和淘汰；
`SavedWorldSpawnTest` 检查存档优先与只读；`RwgChunkBiomeReplayTest`、`RwgSpawnCavesTest`
检查阶段顺序、缓存、负坐标和原生洞穴几何；Viewer 的 `GtnhSpawnOracleTest`、
`GtnhSpawnSelectionTest`、`GtnhBiomeWorkerClientTest` 检查异步快照、选中信息和协议。
`InstalledRwg2ReferenceTest` 对照本机 RWG2 2.0.6，两种噪声、种子 0/-47、原版/BOP 两类规则，共八条游走的方块、高度、坐标和步数一致。参考环境关闭普通洞穴以匹配独立参考生成器；完整整合包首次建档精度仍待验收，普通洞穴几何另有测试。
测试中的原生块注册不可用时使用独立块实例，不修改 JVM 的 `Blocks` 静态常量。

算法依据：[GTNH RWG alpha-1.5.2](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkGeneratorRealistic.java)、
[BOP 官方提供器](https://github.com/Glitchfiend/BiomesOPlenty/blob/fc7f1f6392a16224d19538900acedd152ff4f350/src/main/java/biomesoplenty/common/world/WorldProviderSurfaceBOP.java)
及开发环境中的 Forge 1.7.10 Java 源码。原始调查保存在[历史归档](../archive/README.md)。
