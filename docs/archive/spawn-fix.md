# 出生点修复（v0.3.1 补丁，协议 25）

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

后续实测发现纯种子估算仍有偏差，且选中信息会保留旧坐标。证据及显示修复见
[出生点实例调查](spawn-selection-fix.md)。下述“修复”不代表完整生成阶段的精确选址已实现。

此包包含此前的小行星修复，并修正出生点一直落在原点及刷新后仍保留旧位置的问题。
同时替换 Viewer 和 Worker，重启 GTNH；两端文件名仍为 v0.3.1，协议升级为 **25**。
原 v0.3.1 和小行星补丁归档保持不变，请使用 `Amidst-GTNH-v0.3.1-spawn-fix.zip`。

## 用户可见行为

- 加载了同种子的主世界时，直接读取游戏提供器返回的 `World.getSpawnPoint()`，
  显示“存档世界出生点”，保留 X/Y/Z；读取不会加载或生成区块。
- 主菜单或游戏打开了其他种子的世界时，独立重放请求种子的 RWG 地表和选址随机数，
  显示“估算世界出生点”。不使用另一个种子的存档位置，也不人为套用 ±700～±2000 的偏移范围。
- 地图标记、“前往出生点”及结构坐标导出使用同一份快照。进入存档、切换同种子存档、
  修改存档出生点或手动刷新后重新获取；刷新前发出的旧请求不能恢复旧标记。
- 初次估算在后台进行，地图先正常加载。位置未就绪或提供器不支持时不画原点标记，
  “前往出生点”会提示等待预览或加载对应存档。等待中的图标不会被缓存成永久空结果。
- 预测分段遵守 Worker 的查询预算；单个原生地表回调不能中断。较慢的请求超时后保留
  随机数状态，下次查询继续计算；不会每次重新开始 1000 次地表检查。

世界出生点不同于新玩家首次落脚点、床的位置或模组随机传送点。本修复显示前者。

## 源码依据与根因

1. [GTNH RWG alpha-1.5.2 / ChunkManagerRealistic.java](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkManagerRealistic.java)
   的 `findBiomePosition` 返回 `null`。这表示从原点开始后续试探，不表示已经找到原点出生点。
2. Minecraft/Forge 1.7.10 的 `WorldServer.createSpawnPosition` 使用 `Random(seed)`：
   初始群系搜索后调用提供器的 `canCoordinateBeSpawn`，不满足时 X/Z 各移动
   `nextInt(64)-nextInt(64)`，最多移动 1000 次。第 1000 次移动后直接结束，不再判断新位置。
   项目本地对应源码在 `gtnh-worker/build/rfg/minecraft-src/java/net/minecraft/world/`。
3. [BOP 官方 1.7.10 源码 / WorldProviderSurfaceBOP.java](https://github.com/Glitchfiend/BiomesOPlenty/blob/fc7f1f6392a16224d19538900acedd152ff4f350/src/main/java/biomesoplenty/common/world/WorldProviderSurfaceBOP.java)
   接受沙、石头，或属于允许出生群系的雪层；并不是原版的草方块规则。
   源码的清空检查循环重复读取同一方块，没有使用循环偏移，重放没有擅自改成邻域检查。
   BOP 不在当前 GTNH 组织仓库列表中，此处使用其官方上游的固定提交，未使用反编译来代替源码判断。
4. 旧 Worker 只检查 `biome.topBlock == Blocks.grass`。RWG 多数群系的默认 topBlock 是草，
   原点因此提前通过，既没读取地表方块，也没应用 BOP 规则。
5. [GTNH RWG / ChunkGeneratorRealistic.java](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkGeneratorRealistic.java)
   的地形、地图生成和表面替换顺序由现有 `RwgChunkBiomeReplay` 重放。
   新读取保留这些阶段后的方块列，从 Y=63 向上找连续非空气区间，
   不再把群系默认材质或最高悬空方块当作出生判定地表。

## 维护入口

- `SpawnSearch`：提供器规则、方块身份、随机数和可续算状态；测试不依赖完整 Forge 注册流程。
- `SurfaceBiomeSampler.getSpawnSurface` / `RwgChunkBiomeReplay.spawnSurfaces`：完整地表重放和列索引。
  缓存只留 128 个区块的地表摘要，不持有整片三维方块数组。
- `SavedWorldSpawn`：匹配种子后读取存档提供器坐标，复制结果，避免后续原地修改污染快照。
- `BiomeWorkerServer.sampleSpawn`：存档优先；无匹配世界才走独立种子上下文。
  不认识的提供器返回 `UNAVAILABLE`，不猜测其规则。种子搜索缓存上限为 4。
- `GtnhSpawnPoint` / `GtnhSpawnOracle`：有来源的不可变结果与可刷新的共享快照。
  网络读取只在后台世界状态轮询中进行，UI 的坐标和图标读取不访问网络。
- `WorldBuilder`、`SpawnProducer`、`GtnhRoguelikeDungeonProducers`：共享快照，去除 GTNH 出生点的不可变副本及永久图标缓存。
- 协议 25：`spawnX/spawnY/spawnZ` 可空，`spawnSource` 为 `RECORDED`、`ESTIMATED` 或 `UNAVAILABLE`。
  已记录坐标缺少 Y 或完整坐标缺失时客户端拒绝该回复，避免 JSON 默认零值变成假原点。

## 验证和适用边界

回归覆盖 BOP/原版不同判定、雪层群系限制、RWG 空初始搜索、随机数在分段间连续、
1000 次移动边界、负坐标列索引、海平面上方的空气间隙、合法原点、存档种子匹配、
同种子不同存档、命令改点、旧回复失效、菜单和地图一致以及协议缺失值。
发布时一起运行小行星、地图缓存、导出及其他已有测试，并验证 Worker 的 SRG 映射。

**纯种子结果仍是估算**：重放到洞穴/地物覆盖之前，不派发可能写世界的 Forge 事件，
不重现其他模组接管出生、后续 `setSpawnLocation` 调整、玩家随机出生偏移或存档修改。
达到尝试上限的结果也保留“估算”标记。已加载匹配世界的结果以游戏保存的坐标为准。
本次未运行完整 GTNH 新建存档实测，不能将自动化规则测试等同于逐种子游戏验收。

构建后打包：

```powershell
.\tools\Package-Release.ps1 -PackageSuffix spawn-fix -ReleaseNotes docs/spawn-fix.md
```

输出配套二进制、当前工作树源码 ZIP、逐文件哈希与测试记录。
