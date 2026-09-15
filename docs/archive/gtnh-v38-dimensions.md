# v38：罗斯 128b / Deep Dark 采样加速及罗斯群系修正

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

## 调用链与精度结论

检查本机 GTNH 2.9.0-beta-3 的 `gregtech-5.09.54.133.jar`、`Galacticraft-3.4.33-GTNH.jar` 和 `extrautilities-1.2.12.jar`：

- 罗斯的 `WorldProviderRoss128b.getWorldChunkManagerClass()` 返回原版 `WorldChunkManager.class`。Galacticraft 的 `WorldProviderSpace.registerWorldChunkManager()` 实际通过 `WorldChunkManager(World)` 构造它。因此类名相同不代表只需读取管理器结果；种子、世界类型和后续处理也需要一致。
- `ChunkProviderRoss128b.provideChunk()` 读取最终分辨率的 `loadBlockGeneratorData()`，然后修改群系数组，再写进 Chunk。v37 漏掉了这些替换。
- Deep Dark 的 Provider 也构造 `WorldChunkManager(World)`。`ChunkProviderUnderdark` 将最终群系数组直接写入 Chunk，没有罗斯的替换。`denyDecor` 造成的临时种子加一仅在装饰过程中启用，不能当作该维度群系图的永久种子偏移。

罗斯的替换按真实生成器顺序执行，使用运行时注册 ID，不按显示名称匹配：

| 原群系 | 写入区块的群系 | 条件 |
| --- | --- | --- |
| Mushroom Island | Taiga | 始终 |
| Mushroom Island Shore | Stone Beach | 始终 |
| Thaumcraft Taint | Taiga | Thaumcraft 已加载 |
| Thaumcraft Magical Forest | Birch Forest | Thaumcraft 已加载且 `crossModInteractions.disableMagicalForest=true` |

神秘时代的判断仍针对替换前的原始 ID，保留原生成器的覆盖顺序。已加载、同种子维度中的实际 Chunk 群系优先，不对实际值重复替换。查询不会创建、加载或生成区块。

未加载维度的管理器采用当前同种子存档的世界类型；主菜单任意种子预览采用已注册的 RWG，无法找到时才回退 DEFAULT。已加载的罗斯/Deep Dark 使用其实际管理器。RWG 的 WorldType 本身没有重写原版的 GenLayer 生物群系层；它的主世界专属 ChunkManager 不应误用于这两个维度。

## 性能实现

`ManagerBiomeCache` 合并原本零散的 16×16 区块缓存生成，一次生成 64×64 方块的**最终**群系页。缓存最多 64 页，属于特定种子和管理器；最近页直接索引。罗斯、Deep Dark 中使用原版管理器的路径，以及 BOP Hell 的已核对路径启用批量接口；未知管理器继续调用其逐点虚方法，只复用已查到的结果，避免绕过额外规则。

step=4 仍读取原来的 `x + column*4, z + row*4`，不改用 `getBiomesForGeneration()`，不降低分辨率、不插值猜测。这与主世界地图的中心点偏移规则不同，不能直接混用。

下界和太空的逐点路径按 16×16 个输出像素分段，接入 v37 的 tick 预算及约 4 ms 请求轮转。时间到后保留游标、结果和已生成页。暮色原有的 1:4 整图 GenLayer、末地岛屿栅格、月球填色保留其原本高效的整体路径，避免拆分导致重复工作。纯预测的完整瓦片也会缓存；缓存键包含种子、维度数字 ID、维度键、坐标、尺寸、步长。切换世界清理相关整图缓存，读取真实维度时绕过整图缓存。

## 验证

`ManagerBiomeBenchmark` 使用真正的原版 WorldChunkManager/GenLayer，执行 v37 的逐点循环与 v38 的分页、分段读取。相同种子，每项四个 128×128、step=4 瓦片；预热后交错运行 5 轮，逐项比较全部 65,536 个结果。

| 受控输入区域 | 原逐点循环（四瓦片中位数） | 分页采样 | 吞吐比 |
| --- | ---: | ---: | ---: |
| 罗斯测试负坐标区域 | 985.018 ms | 61.192 ms | 16.097× |
| Deep Dark 测试远坐标区域 | 772.532 ms | 49.266 ms | 15.681× |

这是基础 GenLayer 计算的对照，不含真实整包事件、网络、图标、游戏 tick 等待和罗斯新增加的最终群系替换。不能据此承诺实际游戏整体速度提高 16 倍。罗斯最终输出会有意修正 v37 的错误，不应要求它与 v37 的完整输出处处相同。

`InstalledRossRulesTest` 从指定的实际 GregTech JAR **提取并执行群系替换循环**，对照所有已注册群系、Thaumcraft 开关、魔法森林配置和特殊 ID 重叠。它不是将本项目的规则再抄一份作期望值。其余测试覆盖原版层的多种子逐点一致性、负坐标和分页淘汰、未知管理器的逐点覆盖，以及分段后边缘尺寸和像素计数。

没有自动将新版写入正在运行的 GTNH；真实客户端的 v38 速度和地图对照需要替换 Worker 并重启后复测。原生整页生成仍不可抢占，预算仍是软上限。其他维度的结构预测未在本轮全面改写。

完整构建通过：Viewer 119 项测试通过、10 项开发工具测试跳过；Worker 51 项通过，包含实际 GregTech 替换循环对照，未跳过参考 JAR 测试。安装用 Worker 的 SRG 映射与开发 JAR 拒绝检查通过。

## 调试与维护

配套更新 v38 Worker 和 Viewer，只保留一个 Worker JAR。Viewer 继续默认 GTNH 模式、原生控制台及等待 Worker 行为。

```powershell
powershell -ExecutionPolicy Bypass -File .\Profile-WorkerTiles.ps1 -DimensionKey bartworks:ross128b -X 64000 -Z 32000 -Tiles 4 -OutputPath ross-profile.json
powershell -ExecutionPolicy Bypass -File .\Profile-WorkerTiles.ps1 -DimensionKey extrautilities:deep_dark -X 64000 -Z 32000 -Tiles 4 -OutputPath deep-dark-profile.json
```

脚本从握手读取实际维度 ID，报告计算、排队和分段耗时。首次测试使用未缓存区域，再重复同坐标检查缓存效果。

| 行为 | Worker 源码入口（`gtnh-worker/src/main/java/amidst/gtnh/worker/`） |
| --- | --- |
| 分页大小、容量、批量适用管理器 | `ManagerBiomeCache.java` |
| 原始坐标格点、分段游标 | `BiomeSamplingJob.java` |
| 罗斯最终替换和配置 | `Ross128bBiomeRules.java` |
| 罗斯/Deep Dark 的真实上下文、管理器与最终群系 | `SpaceDimensionSampler.java` |
| 各维度请求路由、整图缓存、取消/世界切换 | `BiomeWorkerServer.java` |

完整构建：`gradlew.bat assembleRelease -PrwgReferenceJar=build/biome-accuracy-reference/RWG-alpha-1.5.2.jar -PgregtechReferenceJar=build/biome-accuracy-reference/gregtech-5.09.54.133.jar --offline --console=plain`。未指定参考 JAR 时对应的安装包对照测试会跳过；测试发行包应指定两者。

速度基准：Worker Gradle `benchmarkManagers`，输出 `gtnh-worker/build/reports/performance/managers-v38.json`。参考 JAR 仅用于本地验证，不随发行包分发。维护中如果升级 GTNH 改变了替换循环，应让提取测试明确失败并重新核对，不能静默忽略失败。
