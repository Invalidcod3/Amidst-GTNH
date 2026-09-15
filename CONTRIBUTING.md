# 开发与维护指南

本项目由游戏外的 Java 17 Viewer 和游戏内的 Forge 1.7.10 Worker 组成。
先读本文了解改动边界，再按 [BUILDING.md](BUILDING.md) 构建。
当前用户行为见 [v0.3.1 发布说明](docs/release-v0.3.1.md)，历次算法调查见 [文档索引](docs/README.md)。

## 源码导航

以下路径均相对于仓库根目录。Viewer 路径以 `src/main/java/` 开始；
Worker 路径以 `gtnh-worker/src/main/java/` 开始。

| 职责 | Viewer 入口 | Worker 入口 |
| --- | --- | --- |
| 协议、连接、请求降级 | `amidst/gtnh/worker/GtnhBiomeSource.java`、`GtnhBiomeWorkerClient.java` | `amidst/gtnh/worker/BiomeWorkerServer.java` |
| 瓦片、坐标及生物群系转换 | `amidst/gtnh/worker/GtnhMinecraftInterface.java` | `SurfaceBiomeSampler.java`（接口）、`SurfaceBiomeSamplers.java`（实现）、`SpaceDimensionSampler.java`、`AdditionalDimensionBiomes.java` |
| 配色、维度名称 | `GtnhBiomeColorPalette.java`、`amidst/mojangapi/world/Dimension.java`、`src/main/resources/amidst/i18n/zh_CN.json` | 运行时维度目录及群系采样器 |
| 磁盘缓存、视图恢复 | `amidst/gtnh/cache/`；`amidst/gui/main/viewer/ViewerFacade.java` | `MapCacheIdentity.java`、`MapCachePreparation.java` |
| 结构候选和刷新 | `amidst/gtnh/structure/GtnhRoguelikeDungeonProducers.java` | 各 `*Predictor.java`、`RoguelikeSiteCheck.java` |
| 出生点与选中信息 | `GtnhSpawnOracle.java`、`GtnhSpawnIcon.java`；`WorldIconSelection.java` | `SavedWorldSpawn.java`、`SpawnSearch.java`、`SpawnSearchCache.java`、`RwgSpawnCaves.java` |
| 矿脉与流体 | `amidst/gtnh/prospecting/` | `ProspectingService.java` |
| 准确性报告 | `amidst/gtnh/validation/`；`amidst/gui/export/AccuracyValidationDialog.java` | `AccuracyValidation.java` |
| 距离排序与 JourneyMap | `amidst/gtnh/export/RadialCoordinateLocator.java`、`GtnhMapWaypoint.java`、`JourneyMapAutoImport.java` | `BiomeWorkerServer` 的路径点导入处理 |
| 启动、暂停世界及线程调度 | `amidst/logging/WindowsConsoleLauncher.java` | `WorkerTickHooks.java`、`WorkerTickBudget.java`、`worker/core/` |

测试与对应源码包保持一致，分别放在两端的 `src/test/java/`。
导出序列化、搜索算法和缓存格式应保持不依赖 Swing，以便在无界面环境中测试。

## 必须保留的边界

### 线程和冷启动

- Swing 控件只在事件线程修改。网络和文件准备工作放到后台；返回后先确认窗口仍存在、存档会话仍有效。
- 游戏世界读取和生成算法回调由 Worker 的游戏线程队列执行。不能把世界读取直接迁移到连接线程来提高吞吐量。
- 模组与配置文件指纹由 `MapCachePreparation` 的单个后台任务准备。未准备好时返回 `null`，直接查询地图；不能同步扫描配置目录，不能返回临时指纹。
- `cache_context` 是可选请求。Viewer 最多等待 2 秒，失败后暂停缓存查询 30 秒；不能因此将正常瓦片请求判为离线或重试耗尽。
- 游戏 tick 时间预算是软上限：原生单个区块回调不能抢占。涉及预算、暂停游戏或并发时，必须保留真实线程和连接测试。

冷启动回归入口：`MapCachePreparationTest`、`BiomeWorkerServerTest` 的慢准备并发测试、
`GtnhBiomeWorkerClientTest.coldCacheTimeoutStillLoadsTilesAndBacksOffCacheRequests`。
模拟慢指纹准备时，四个连接和普通地图请求仍须有响应。

### 缓存与存档生命周期

- 持久缓存只存群系栅格和视图。种子相同不代表同一个存档；身份还包含存档路径、生成参数、模组与配置输入。
- 复用群系瓦片前核对当前加载区块的证据。损坏、版本不匹配、权限不足都应退回正常查询。
- `MapStorage` 文件为大端序：4 字节格式标记、4 字节样本数、8 字节 CRC32 值、每样本 4 字节群系 ID。修改格式应改变标记；修改预测语义时检查 `MapCacheIdentity` 的缓存版本前缀。
- 切换存档、手动刷新和区块更新必须传播到结构与探矿显示。旧请求完成后不能重新写回已清空的缓存，保留 generation/session 检查。
- 当前不支持在同一游戏进程中热重载整套模组配置；改变生成配置后应重启游戏。

### 准确性、坐标和维度

- 验证必须比较独立预测与实际记录，不能拿覆盖了实际数据的预测结果再与自己比较。
- 无记录、未加载、模组未提供可靠证据时使用 `UNVERIFIED`，不能当作一致或不存在。准确率分母只包含 `MATCH + MISMATCH`。
- 检查不得主动生成、加载或修改区块。Roguelike 的当前地形选址结果不是建筑存在性的证明；不要持久保存“不生成”名单。
- Viewer 部分额外维度使用内部编号。JourneyMap 和实际世界查询必须经过运行时目录解析为真实维度 ID；下界 X/Z 不应再次除以 8。
- `CoordinatesInWorld.getY()` 在地图平面上代表 Z。路径点的游戏高度 Y 是独立字段；未知高度的导航默认值不能描述为精确高度。
- 向外导出按 X/Z 欧氏距离排序。只有扫描了所有可能更近的瓦片后才能输出候选；距离相同时按 X、Z 排序，相同 X/Z 去重。达到查询预算时只能返回已证明顺序的结果。

## 协议与发布版本

两端是独立构建，保留两份 Java 8 兼容的数据传输类：

- `amidst/gtnh/prospecting/ProspectingData.java`
- `amidst/gtnh/validation/AccuracyReport.java`

修改时同步两份，禁止仅修改一端。`gradle/verify-release-contract.gradle.kts`
由两端构建共同使用，检查这些文件内容一致（忽略 CRLF/LF 差异），检查两端协议常量、
Worker `@Mod` 版本及 Viewer 显示版本。`check`、`jar` 和配套发布构建都会执行。
新增共享数据类时将其加入检查清单；不要在 Worker 数据类中使用 record 或 Java 17 API。

发布时修改 `src/main/resources/amidst/metadata.properties` 中的发行版本、文件名和显示后缀，
以及 `AmidstGtnhBiomeWorkerMod.java` 中的 `@Mod` 版本。
协议变更还需更新 metadata 的 `amidst.worker.protocol` 与两端 `PROTOCOL_VERSION`。
协议号与发行版本相互独立；当前 v0.3.1 使用协议 25，以 metadata 和构建检查为准。

## 代码风格

新增缓存、验证、导出和出生点模块统一使用四空格的 google-java-format AOSP 排版；旧模块局部修改遵循原文件风格。
不要把无关旧代码的全文件格式化混入功能修复。格式化范围维护在 `tools/java-format-files.txt`。

可选格式化工具为 google-java-format **1.28.0 all-deps**，从 Maven Central 下载到忽略的 `build/tools/`。
工具不是运行依赖，源码包不包含其二进制。脚本验证固定 SHA-256，使用 JDK 21 或更新版本执行：

```powershell
New-Item -ItemType Directory -Force build/tools
Invoke-WebRequest 'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.28.0/google-java-format-1.28.0-all-deps.jar' -OutFile build/tools/google-java-format-1.28.0-all-deps.jar
./tools/Format-Java.ps1 -FormatterJar build/tools/google-java-format-1.28.0-all-deps.jar
./tools/Format-Java.ps1 -FormatterJar build/tools/google-java-format-1.28.0-all-deps.jar -Check
```

优先为“为什么这样做”写注释，例如线程所有权、实际数据与候选的区别，以及缓存失效条件。
变更用户文案时同时维护中英文；维度翻译依据记录在 [名称核对](docs/reference/dimension-name-audit.md)。

## 验证与交付

日常改动先运行相关测试；发布运行 `assembleRelease`，不能使用 `-x test`。
可通过以下参数启用真实模组字节码对照（文件由开发者自行提供，不随源码包分发）：

```powershell
./gradlew.bat assembleRelease "-PrwgReferenceJar=C:/reference/RWG-alpha-1.5.2.jar" "-PgregtechReferenceJar=C:/reference/gregtech-5.09.54.133.jar"
./tools/Package-Release.ps1 -PackageSuffix rebuild-20260915
```

参数传给 Worker 测试；缺少参考 JAR 时相关测试会跳过，不能宣称已经完成算法对照。
Viewer 的 10 个 `DevToolRunner` 项是默认禁用的开发工具，不是产品回归测试失败。

打包脚本保存当前工作树源码（含未提交但未被忽略的文件）、逐文件哈希、测试计数和基础提交号。
打包前检查 `git status`，不要把本机配置、令牌、日志和游戏存档加入源码。
发布目录保留旧版本 JAR 和日志；脚本只选择当前 metadata 命名的配套 JAR。
版本相同的归档已存在时脚本拒绝覆盖；重新构建使用明确的 `-PackageSuffix`，保留旧产物。

### 出生点维护

调用链和精度边界统一见[出生点预测](docs/reference/spawn-prediction.md)。
`BiomeWorkerServer` 只负责协议和游戏线程调度；`SpawnSearchCache` 持有独立种子的续算任务，
`SpawnSearch` 负责提供器规则与随机游走；保存的世界坐标由 `SavedWorldSpawn` 读取，始终优先于估算。
不要重新使用已删除的“群系默认地表为草”启发式接口。测试中的冻结参考实现保留历史算法用于对照，不作为生产入口。

### 文档维护

当前入口保持精简：`README.md` 用于快速安装，`docs/usage.md` 用于操作，
`docs/release-v0.3.1.md` 汇总当前发行状态，本文用于源码边界。构建命令统一在 `BUILDING.md`。
同版本变更直接合并到这些文档；算法依据放 `docs/reference/`，历史测量和旧补丁记录放 `docs/archive/`。
移动文档后核对 Markdown 相对链接、源码注释中的路径及随包安装说明，不能仅修改文档索引。

完整游戏验收应记录游戏版本、模组版本、种子和存档状态，至少覆盖：

1. 全新游戏进程冷启动，无缓存和有缓存各一次；瓦片可加载，日志没有缓存初始化阻塞。
2. 拖动缩放、关闭后恢复视图、缓存不可写时正常查询。
3. 同种子不同存档切换，刷新后地牢选址重新判断；暂停游戏仍能查询。
4. 主世界、下界和额外维度各导入一个路径点，核对实际维度及 X/Z。
5. 已加载区域验证与未加载区域验证，后者应保留无法验证；取消不会混入另一个存档的报告。

自动化测试不能替代这些游戏验收。PR 说明应写清问题、最终行为、测试结果，以及尚未执行的验收项。
