# 出生点估算：补齐洞穴阶段

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

本补丁继续使用 v0.3.1、协议 25，包含此前全部修复。**需要更新 Worker 并重启游戏**。
此前的 `spawn-selection-fix` 只更新 Viewer；本补丁修改了实际估算过程。

## 改进内容

出生点估算此前在 RWG 地表替换后直接读取地表方块。现在按官方
`ChunkGeneratorRealistic.provideChunk` 的顺序，在地表替换后执行原生洞穴生成，再按
原版/BOP 提供器的规则判断能否出生。洞穴开口会改变海平面以上的连续方块列，
因此某些位置的接受结果及随机搜索最终停下的坐标会改变。

- 使用 `TerrainGen.getModdedMapGen(new MapGenCaves(), CAVE)` 获取运行时生成器，遵守
  `ConfigRWG.generateCaves`；不自行近似洞穴形状。
- 输入完整的地形、地表替换结果，保留洞穴的避水和表层回填条件。不能用全石头数组代替正式预测输入。
- 洞穴只修改独立预览世界的临时方块数组；不读取存档地表作为预测输入，不调用区块地物覆盖或结构写入。
- 普通生物群系瓦片不增加洞穴运算。出生点缓存只接受洞穴阶段成功后的结果，失败后重放完整区块再重试。
- 原生洞穴使用自己的种子随机数，不消耗出生点随机游走的随机数。

## 实例验证与限制

种子 `-8138049151491905853` 的存档世界出生点仍以只读核对结果 `(-58, 64, -180)` 为准。
这并不等同于新估算已得到该坐标。

新增原生 `MapGenCaves` 几何测试使用这个种子，在目标区块 `(-4,-12)`、局部列 `(6,12)`
记录洞穴算法请求挖掘的数组位置。全实心、无水阻挡条件下，只有 Y=25–27 和 35–37；
Y≥63 没有洞穴。实际水体可以阻止挖掘，不能增加该几何包络之外的洞穴。
因此**原版洞穴不足以解释该存档 Y=65 的地表**；模组替换的洞穴生成器不在这项排除结论内。
本次没有用新 Worker 在完整 GTNH 中重放该种子的首次建档，不能宣称该实例已精确吻合。

仍未重放的阶段包括 `ReplaceBiomeBlocks` 第三方事件、结构与区块地物覆盖，以及其他模组的世界生成修改。
其中一些会读写相邻区块，不能直接放到真实世界里执行来冒充独立预测。
结果继续标为“估算世界出生点”；同种子的已加载世界仍优先返回游戏记录的出生点。
当前没有跨种子误差统计，不给出未经测量的准确率或误差半径。

## 维护与测试

- `RwgSpawnCaves`：配置和运行时洞穴生成器接入。
- `RwgChunkBiomeReplay.spawnSurfaces`：地表替换完成后雕刻洞穴，再提取表面。
- `RwgSurfaceBiomeSampler.getSpawnSurface`：独立缓存和失败重试；生物群系查询不会提前写入未完成的出生点表面。
- `RwgSpawnCavesTest`：禁用配置、报告种子的原生几何、不同区块调用顺序不改变结果、禁止加载存档区块。
- `RwgChunkBiomeReplayTest`：地表回调先于洞穴、海平面扫描、负坐标、绘图缓存隔离、异常不缓存、成功后复用。

源码依据：

- [GTNH RWG alpha-1.5.2 / ChunkGeneratorRealistic](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkGeneratorRealistic.java)
- [GTNH RWG alpha-1.5.2 / ConfigRWG](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/config/ConfigRWG.java)
- 项目开发环境中的 Forge 1.7.10 Java 源码 `MapGenBase`、`MapGenCaves`；本次未从字节码推导算法。
- [先前实例调查](spawn-selection-fix.md)、[提供器与随机选址规则](spawn-fix.md)。

## 构建和安装

```powershell
.\gradlew.bat assembleRelease -PrwgReferenceJar=build/biome-accuracy-reference/RWG-alpha-1.5.2.jar -PgregtechReferenceJar=build/biome-accuracy-reference/gregtech-5.09.54.133.jar --offline --console=plain
.\tools\Package-Release.ps1 -PackageSuffix spawn-cave-replay -ReleaseNotes docs/spawn-cave-replay.md
```

产物：`Amidst-GTNH-v0.3.1-spawn-cave-replay.zip`、配套源码 ZIP 和 SHA-256 校验文件。
关闭游戏后替换 `mods` 中的 Worker，只保留一份；使用包内 Viewer，再启动游戏。
历史发布归档保持不变。
