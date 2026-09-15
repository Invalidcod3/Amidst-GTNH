# 出生点预测调用链核对

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

> 历史调查：下文描述原 v0.3.1 的问题。现已在 [出生点补丁（协议 25）](spawn-fix.md) 中修复，
> 新实现及纯种子估算边界请以补丁说明为准。

核对对象：v0.3.1 工作树。用户观察到多个种子的 Viewer 出生点一直为 `(0,0)`，
实际世界出生位置通常偏离原点数百至数千方块。
本次为源码与本地参考 JAR 字节码核对，尚未修改出生点实现或重新打包发行版。

## 结论

当前出生点查询只做群系级近似，未执行游戏实际的出生点选址方法，也不读取已加载存档的出生点。
RWG 初始群系搜索返回 `null`，于是查询从 `(0,0)` 开始；只要原点群系的 `topBlock`
为草方块，近似判断立即通过，随机试探循环一次也不会执行。这条路径会稳定返回原点。
该结果与用户描述吻合，但没有给定种子的逐点游戏对照，不能据此断言所有 RWG 种子都必然返回原点。

## 1. Viewer 到 Worker

```text
WorldBuilder.from / initInterfaceAndGetFeatures
  → GtnhMinecraftInterface.getWorldSpawn(seed)
  → GtnhBiomeWorkerClient.sampleSpawn(seed, dimension=0)
  → TCP command="spawn"
  → BiomeWorkerServer.sampleSpawn
  → SurfaceBiomeSampler.findSpawnBiomePosition(Random(seed))
  → WorldChunkManager.findBiomePosition(0, 0, 256, spawnBiomes, random)
  → RWG ChunkManagerRealistic.func_150795_a(...) → null
  → 起始 X/Z = 0/0
  → isLikelySpawnCoordinate(x,z)
  → getBiomeAt(x,z).topBlock == Blocks.grass
  → 条件满足时直接回复 spawnX=0, spawnZ=0
```

入口文件：

- `src/main/java/amidst/mojangapi/world/WorldBuilder.java`
- `src/main/java/amidst/gtnh/worker/GtnhMinecraftInterface.java`
- `src/main/java/amidst/gtnh/worker/GtnhBiomeWorkerClient.java`
- `gtnh-worker/src/main/java/amidst/gtnh/worker/BiomeWorkerServer.java`
- `gtnh-worker/src/main/java/amidst/gtnh/worker/SurfaceBiomeSampler.java`

`spawn` 的 JSON 回复直接转换为地图 X/Z，没有在这个路径中做比例缩放。
Worker 虽然可能选择已加载世界的群系采样器，但 `sampleSpawn` 本身没有调用 `getSpawnPoint()`。

## 2. 游戏创建世界出生点

本地 Forge 1.7.10 开发源码中的调用链为：

```text
WorldServer.initialize
  → WorldServer.createSpawnPosition(WorldSettings)
  → provider.canRespawnHere()
  → ForgeEventFactory.onCreateWorldSpawn（事件接管时直接返回）
  → worldChunkManager.findBiomePosition(...)（RWG 返回 null）
  → 从 (0,0) 开始
  → provider.canCoordinateBeSpawn(x,z)
  → 不合格则按种子随机数移动 X/Z，最多移动 1000 次
  → WorldInfo.setSpawnPosition(x,y,z)
```

初始群系搜索半径 256 并不限制后续随机试探范围。每次 X/Z 分别叠加
`nextInt(64) - nextInt(64)`，后续位置可以离开初始范围。
用户观察到的 ±700～±2000 是具体世界的结果，不能用作统一偏移量或强制范围。

实际选址方法由运行时世界提供器决定：

- 原版 `WorldProvider.canCoordinateBeSpawn` 调用 `World.getTopBlock(x,z)`，比较**实际方块**。
- `World.getTopBlock` 从 Y=63 开始向上检查连续的非空气方块，并非读取群系默认材质，也不是从世界最高处向下找地面。
- BOP 2.1.0.2308 的 `WorldProviderSurfaceBOP` 重写了该方法，读取方块、材料及群系列表，条件不同于原版草方块规则。必须遵循该版本的实际实现，不能因为模组方法名相同便套用原版判断。
- 当前 Worker 用 `biome.topBlock` 替代上述判断。河流、地表替换、岩石或其他局部地形都可能让群系声明与当前位置的方块不同。
- 现有群系重放器 `RwgChunkBiomeReplay` 明确只重放洞穴和地物生成之前的阶段，并跳过有副作用的 Forge 事件；不能直接把它当作完整出生点生成器。

本地已有历史握手记录曾报告提供器为 `biomesoplenty.common.world.WorldProviderSurfaceBOP`。
这支持继续检查 BOP 路径，但不替代对用户当前游戏实例的运行时确认。

## 3. 世界出生点与玩家进入位置

世界出生点读取链：

```text
World.getSpawnPoint()
  → provider.getSpawnPoint()
  → WorldInfo.getSpawnX / getSpawnY / getSpawnZ
```

新玩家创建还会经过：

```text
EntityPlayerMP 构造
  → provider.getRandomizedSpawnPoint()
  → 从世界出生点开始，根据游戏模式、天空条件和 Forge 配置添加随机范围
```

因此世界出生点与新玩家首次站立点需要分别对照；玩家床、命令或其他模组的传送同样不能当作原始选址结果。
有存档时应优先读取当前世界提供器返回的出生点，该读取本身不需要生成区块。
仅按种子重新计算也无法复现玩家通过命令修改后的世界出生点。

## 4. Viewer 还存在出生点快照问题

`WorldBuilder` 把查询结果放入 `ImmutableWorldSpawnOracle`，随后又将坐标快照传入
`GtnhRoguelikeDungeonProducers` 的 `final worldSpawn` 字段。普通出生点生产器还有自己的图标缓存。
当前结构缓存清理不会改变这两个坐标来源。

后续修正必须同时处理地图标记与“前往世界出生点”入口，并在下列情况重新获取坐标：

- 主菜单预览进入匹配种子的真实存档。
- 切换到同种子的另一存档。
- 手动刷新或世界出生点发生更改。

异步请求返回时需检查会话与请求代次，防止旧存档的回复写回新视图。

## 修正边界与验收依据

1. 已加载匹配种子的主世界：从游戏线程读取 `world.getSpawnPoint()`，标记为存档世界出生点；不能误读另一个种子的存档。
2. 纯种子预览：按实际提供器规则重放安全的地表读取，并明确生成阶段覆盖范围。未验证的结果应标记为估算，不能把空结果当作已确认的原点。
3. 若选址需要较多区块计算，将随机数状态与尝试次数保存为可续算任务，遵守游戏 tick 预算；不能把原有同步循环直接改成最多 1000 次真实区块生成调用。
4. 验收需覆盖原点草群系但实际方块不合格、BOP 提供器规则、RWG 初始搜索为空、读取存档出生点、切换同种子存档以及菜单和图标同步。
5. 实际对照使用 `WorldInfo` / `getSpawnPoint()` 的坐标；另记录首次玩家位置，不能混为一项。

## 参考输入和复查方法

本地 Minecraft/Forge 源码位于 `gtnh-worker/build/rfg/minecraft-src/java/`，
由构建工具生成，不随源码版本控制。重点方法为 `WorldServer.createSpawnPosition`、
`WorldProvider.canCoordinateBeSpawn/getSpawnPoint/getRandomizedSpawnPoint`、`World.getTopBlock`。

核对过的参考 JAR：

| 文件 | SHA-256 |
| --- | --- |
| RWG-alpha-1.5.2.jar | `9be5c54b56cf2a08d507a5568dec1537ca6f5e958a2ce9d39e32207870a6bd6f` |
| BiomesOPlenty-1.7.10-2.1.0.2308-universal.jar | `5382d1c156c871c896b6cebd036b80f58ceaff36b09d416b113f32884b7b4a2a` |

使用 JDK 的 `javap -c -p -classpath <参考JAR>` 分别读取
`rwg.world.ChunkManagerRealistic` 与 `biomesoplenty.common.world.WorldProviderSurfaceBOP`。
RWG 的 `func_150795_a` 字节码仅有 `aconst_null; areturn`，确认空值来自真实实现。
SRG 方法名 `func_76566_a` 对应出生坐标检查。

本次没有运行游戏创建新世界或修改存档；上述“确认”指调用链及参考版本字节码，不是完成出生点精度验收。
