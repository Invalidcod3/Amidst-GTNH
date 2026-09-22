# 要塞定位：当前实现与诊断

## 已确认的实现问题

地图和“前往要塞”菜单目前存在两条不同的预测线路：

| 入口 | 调用链 | 群系调整 |
| --- | --- | --- |
| GTNH 地图标记 | `GtnhRoguelikeDungeonProducers → Worker → RwgStructurePredictor.addStrongholds` | 按 RWG 行为跳过 |
| 前往要塞菜单 | `Actions.goToStronghold → World.getStrongholdWorldIcons → StrongholdProducer_Original → BiomeDataOracle` | 执行旧 Amidst 搜索 |

`WorldBuilder` 替换了 GTNH 地图图层，却仍将原版 `STRONGHOLD_PRODUCER` 传给 `World`。
RWG 的 `findBiomePosition` 直接返回 null；旧菜单算法会查询群系并消耗随机数，
影响当前点与后续点的位置。这是确定的实现分歧。菜单在 Swing 事件线程调用查询，
还存在等待网络时阻塞界面的风险。当前尚未修复这条分歧。

Worker 另有兼容性边界：手写公式固定数量、距离和群系搜索行为，没有从
`TerrainGen.getModdedMapGen(..., STRONGHOLD)` 的最终生成器读取参数。
符合当前标准 RWG 路径不等于支持所有生成器替换或旧存档。
`getSampler` 对不匹配的已加载生成器会选择独立 RWG 预览，不能把这种结果描述为该存档的实际预测。

## 坐标含义

Worker 和地图显示起始区块角点 `(chunkX * 16, chunkZ * 16)`，不是传送门房间。
原生生成器尚未拥有建筑记录时，其候选定位使用区块中心 `(+8,+8)`；已有建筑时，
`MapGenStructure` 定位可以使用第一个组件的中心。传送门房间还可位于其他坐标。
因此小幅坐标差不一定代表起始区块错误，但不能用这个区别解释任意远距离偏差。

## 报告实例

种子 `5942749603128060499`，用户报告地图标记 `(-464,-864)`，尚未提供实际游戏坐标。
对当前 Worker 的真实 `addStrongholds` 方法与源码编译出的 Forge 1.7.10 `MapGenStronghold`
进行对照，群系管理器按已核对的 RWG 实现返回 null；诊断禁止加载区块或创建建筑。

| 当前 Worker 起始区块角点 | 原生候选区块中心 |
| --- | --- |
| (-592, 816) | (-584, 824) |
| (-416, -944) | (-408, -936) |
| (1104, 112) | (1112, 120) |

共检查七个种子的 21 个点，扣除中心偏移后全部一致。该检查验证标准 RWG 的基础公式，
不包含完整 GTNH 运行时事件、变更过的存档或实际建筑放置。
报告的 `(-464,-864)` 不在当前结果中，与附近角点相差 `(-48,+80)`，约 93.3 格。
不能直接认定是原生环形公式错误，也不能用 `(+8,+8)` 解释。

另外，用受控的全平原输入运行旧 Viewer 的真实群系搜索，菜单得到
`(-576,736)`、`(-336,-816)`、`(1008,144)`，并执行三次群系查询。
这是两条线路可以分歧的复现，不是该种子实际 GTNH 群系的测量。

本机未发现该种子的存档，Worker 未在线。进一步归因需要：产生标记的两端版本、
标记完整名称、实际要塞位置或 `Stronghold.dat` 起始区块记录。
用户明确该坐标来自地图，因此不能把菜单问题直接当作本实例根因。

## 修正方向

- 地图、菜单、导出共享 Worker 提供的同一定位结果，菜单查询放到后台并校验会话。
- 区分起始区块、定位目标和传送门；没有证据时不把候选称为精确房间坐标。
- 核对最终生成器与加载存档的生成条件，未知实现明确报不支持，避免静默套用 RWG。
- 独立预测与存档生成记录分开保存、比较；不能调用会生成结构的定位方法冒充只读验证。

源码依据：[RWG 生成器接入](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkGeneratorRealistic.java)、
[RWG 群系管理器](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkManagerRealistic.java)，
以及项目开发环境中的 Forge `MapGenStronghold`、`MapGenStructure`、`ChunkCoordIntPair` Java 源码。
