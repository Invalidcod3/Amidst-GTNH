# 村庄定位调查与版本兼容边界

调查日期：2026-10-10；项目基线：`2fb03a4002c7fe4942fa22e1398e40cc92b3374d`。
本轮核对源码、修复主世界生产预测器，并保留可复跑的选点算法对照工具。
主世界修复随 v0.3.3 交付；下文保留修复前的测量，供后续版本回归；月球生产算法未改动。
用户已反馈主世界村庄定位问题解决；尚未取得对应的游戏版本、种子、坐标或存档，
该反馈不替代完整游戏验收，月球生成流程的推断仍待验证。

## 主世界：已确认的实现分歧

GTNH 地图使用 `GtnhRoguelikeDungeonProducers → BiomeWorkerServer.sampleStructures →
RwgStructurePredictor.addVillages`。旧 Amidst 的 `VillageProducer` / `VillageAlgorithm`
不是这条 GTNH 地图路径的村庄算法。

RWG alpha-1.5.2 创建 `MapGenVillage(size=0, distance=24)`，随后调用
`TerrainGen.getModdedMapGen(..., VILLAGE)`。Village Names 在该事件中以 LOW 优先级
将它替换为无参构造的 `MapGenVillageVN`；RWG 传入的 distance=24 没有保留下来。
Village Names 的开关和实际事件结果均是兼容条件，不能仅根据“装了该模组”选择算法。

| 项目 | 修复前 Worker | Village Names 4.5.18-GTNH |
| --- | --- | --- |
| 最大间距 / 区域边长 | 固定 24 区块 | Median + Range |
| 最小间距 / separation | 固定 8 区块 | max(1, Median − Range) |
| 候选随机范围 | nextInt(16) | nextInt(最大间距 − 最小间距) |
| 准入 | RWG 高度与平坦度检查 | 区块中心的实际群系名称在配置名单内 |
| 原点无村庄半径 | 未使用 | 配置 `Village-Free Radius` |
| 最终建筑有效性 | 未重放组件组装 | 非道路组件数量 > 2，保存 `Valid` |

GTNH 官方配置快照启用 `Activate New Village Generator=true`，Median=40、Range=10、
Radius=0。因此该配置下真正的选点参数是 **50 / 30，随机范围 nextInt(20)**。
这不是“把距离改为 40”就能修正的问题，也不能把 50 / 30 硬编码为所有 GTNH 版本。

RWG 的 `areBiomesViable` 忽略群系列表，要求中心地形高度至少 62，周边采样高度差
小于 22。Village Names 的候选检查改用 `world.getBiomeGenForCoords(chunk*16+8)`
与 `spawnBiomesNames` 的名称匹配。旧 Worker 的 RWG 筛选会同时造成漏标和误标。
预测未生成区域时还需用最终地表群系，而非仅用 RWG 原始地形群系。

## 主世界：2026-10-10 修复

`VillageGenerationRules` 将参数读取与候选枚举分开：

- 已连接存档直接读取 RWG 区块提供器的最终 `villageGenerator`；纯种子预览按 RWG
  构造参数调用同一个 Forge VILLAGE 选择事件，每个采样器只选择一次。
- 最终类为原版 `MapGenVillage` 时，从实例读取间距，保留 RWG 地形准入。
- 最终类为 `MapGenVillageVN` 时，读取它声明的两项间距字段，避免误读父类的同名字段；
  从运行时配置读取半径和群系名单，并调用该版本自己的 `unpackBiomes` 解析名单。
- VN 按最终群系名称精确匹配，保留区块单位的半径和原生 int 运算语义，包括远坐标溢出。
  日常地图可读取已加载区块的最终群系；准确性报告强制走独立预测，避免自我对照。
- 未识别的生成器子类或配置接口明确报错，不静默套用固定 24 / 8。不生成/加载区块，
  不调用结构组装或名称存储 API。改配置后仍需重启游戏。

修复后的参考 JAR 对照使用实际 Worker 参数适配器、官方 VN 候选指令与群系解析指令，
覆盖同一组正负坐标和种子：

| 种子 | VN 候选 | 修复后 Worker 候选 | 漏标 | 额外候选 |
| --- | ---: | ---: | ---: | ---: |
| 0 | 108 | 108 | 0 | 0 |
| 1 | 106 | 106 | 0 | 0 |
| -1 | 106 | 106 | 0 | 0 |
| 5942749603128060499 | 107 | 107 | 0 | 0 |

这四个种子共 1048576 个区块无分歧。另测 24 / 8 下的允许/禁止群系与相反 RWG
地形结果、名称大小写、空名称/附加类型信息、null 名单、73 / 21 实例间距以及
5、80、50000 的半径。共 **1916928 个受控区块检查，无分歧**。

11 项新增回归测试覆盖上述边界、独立验证、负坐标和相邻窗口、未知替换拒绝、Forge
最终选择及重复查询。原版分支另与 Minecraft 原生选择器在 24 / 8 和 36 / 8 下对照。
这些结果仅证明受控选点一致，没有完成 GTNH 游戏内建筑验收或所有版本的兼容性认证。

## 主世界：修复前对照和建筑精度边界

工具直接提取官方 Village Names 运行 JAR 的构造函数及候选检查指令，
与调查基线的 Worker 村庄方法对照。输入固定为允许的 Plains，
并让 Worker 的地形准入全部通过，以单独测量间距规则的分歧。
每个种子扫描 X/Z 区块 `[-256,255]`。

| 种子 | VN 候选 | Worker 候选 | 共同候选 | VN 候选漏标 | Worker 额外候选 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 | 108 | 464 | 1 | 107 | 463 |
| 1 | 106 | 461 | 0 | 106 | 461 |
| -1 | 106 | 464 | 0 | 106 | 464 |
| 5942749603128060499 | 107 | 459 | 0 | 107 | 459 |

这些是**受控选点对照，不能当作真实 GTNH 世界的村庄准确率**。
例如种子 0 的 VN 独有起始区块 `(-250,104)`，对应 Worker 惯用的 +4 标记
`(-3996,1668)`；Worker 独有起始区块 `(-256,30)`，对应 `(-4092,484)`。

另将参考 VN 的间距设为与旧 Worker 相同的 24 / 8，隔离准入条件：

- Plains 被 VN 允许、Worker 地形准入返回 false：VN 有 28 个候选，Worker 为 0。
- 群系名称不在 VN 名单、Worker 地形准入返回 true：VN 为 0，Worker 有 28 个候选。

候选选点正确后仍需区分实际建筑。VN 保存记录的名称仍是 `Village`，结构起始类型
是 `MapGenVillageVN`；记录中的 `Valid=false` 表示组装结果没有足够的非道路组件。
当前 `AccuracyValidation.verifyStructure` 只检查记录是否存在，不读取 `Valid`。
所以当前报告的 MATCH 只能证明“有起点记录”，不能证明“有有效、完整的村庄建筑”。

Village Names 的名称数据另存于 `villagenames3_Village` 的 `NamedStructures`，
包含 `signX/Y/Z`、`namePrefix/Root/Suffix` 等字段。名称/告示牌坐标、起始区块、
首组件中心、动态村庄集合中心不是同一个位置；不能通过村庄名字倒推候选选点。
`VNWorldDataStructure.forWorld` 在缺少记录时会创建数据，后续只读验证不应调用它。

## 月球：配置没有调整村庄密度

Galacticraft 3.4.33-GTNH 的 `ConfigManagerCore` 只有月球村庄生成开关
`disableMoonVillageGen`，没有村庄间距或密度参数。GTNH 官方配置快照
`config/Galacticraft/core.conf` 设置 `Disable Moon Village Gen=false`。

月球直接创建 `MapGenVillageMoon`，不调用主世界的 VILLAGE 替换事件。
Village Names 的 40 / 10 配置不会修改这条月球生成路径。
已核对的 Galacticraft 3.4.33、3.4.34 及 master 快照的月球选点源码相同：

- 区域边长 32 区块，separation=8，salt=10387312。
- 负坐标先减 31，再使用 Java 整数除法计算区域。
- **随机种子使用调整后的区块坐标，不是区域坐标**。不能改成原版主世界公式。
- `StructureVillageStartMoon.isSizeableStructure()` 恒为 true。

因此 32×32 区块区域并不保证恰好一个候选。种子 0 的 256 个区域中有 88 个区域
没有起点，65 个区域有多个起点，最多 5 个。这是本次核对版本的实际选点行为，
不应将其误解释成用户配置了不同密度。

## 月球：已验证内容与待验证生成流程

直接提取项目已有 Galacticraft 3.4.33-GTNH JAR 的原生候选检查指令，
对照当前 `MoonStructurePredictor.isVillageStart`：

- 7 个种子：0、1、-1、5942749603128060499、-8138049151491905853、
  Long.MIN_VALUE、Long.MAX_VALUE。
- 每个种子扫描 262144 个正负坐标区块，共 **1835008 次检查，0 分歧**，
  原生算法共接受 1860 个起点。
- 完整 `predict` 的 540 个窗口/开关用例覆盖负坐标、区块/瓦片边界、小范围查询
  和 villagesEnabled=false，村庄标记全部与原生选点对应。

这证明所测范围内的选点及输出边界一致；没有执行完整模组初始化、运行时修改、
存档区块加载或实际建筑放置，不能证明所有地图标记都有建筑。

月球生成流程有一个高优先级待验证项：`ChunkProviderMoon.provideChunk` 未调用
村庄的结构起点准备；`populate` 仅调用 `generateStructuresInChunk`，后者放置
已准备的结构，不会创建起点。起点准备在 `recreateStructures`，而核对的 Forge
`ChunkProviderServer` / 异步加载路径是在已有区块读取时调用它。
**推断：新区块生成与已有区块重新加载之间可能出现“理论候选存在、建筑未放置”的差异。**
GTNH 的其他补丁、服务端实现及加载顺序可能补充这条流程；尚未用完整游戏验证，
不能将其直接认定为反馈实例根因，也不应让地图工具通过主动生成区块来“修正”它。

当前准确性报告还有明确缺口：`recordKey` 没有 `MOON_VILLAGE → MoonVillage`，
遗漏记录扫描也仅支持主世界。月球本有 `MoonVillage` 结构起点记录，
后续应按运行时维度读取并双向对照，同时保持“起点记录”和“建筑存在”两个验证层级。

当前月球标记是 `chunk*16+8`；第一口井的边界是 `chunk*16+2` 至 `+7`，
整数中心为 `+4`。这个约 4 格的差别可以解释小偏移，不能解释标记处完全没有建筑。

## 为后续校验与多版本兼容保留的边界

| 层级 | 后续实现边界 | 遇到未知版本/实现时 |
| --- | --- | --- |
| 生成器识别 | 读取运行时最终生成器类、模组版本、算法标识；区分 RWG 原版、VN、月球 | 明确未验证/不支持，避免默认套用 24 / 8 |
| 参数读取 | VN 配置与最终生成器参数适配器分开；月球已核对版本使用原生常量 | 保留参数来源，不根据配置文件名或版本字符串猜参数 |
| 选点 | 独立策略保留区域/区块随机种子差异、负数语义、半径检查 | 新策略使用真实 JAR 对照，不自动“纠正”历史算法 |
| 准入 | VN 实际群系名单与 RWG 地形检查分开 | 未识别准入规则时保留候选精度说明 |
| 坐标 | 独立保存起始区块、显示标记、首组件/告示牌目标 | 不将未知目标描述为精确村庄中心 |
| 证据 | 按维度、记录名、结构类型解析；记录存在、Valid、组件放置分开 | 缺少证据使用 UNVERIFIED |
| 生命周期 | 种子、存档会话、运行时配置指纹、生成器策略组成兼容上下文 | 换存档或改配置使相关结果失效 |

适配器应复用已核对的算法族，同时保留每个版本的真实参考输入，避免每个版本复制
一整份预测器。初期不必改变协议；新增可用性或证据字段时需同步 Viewer/Worker。
主世界已接入实际 VN 参数和群系准入；月球后续应接入只读记录校验并调查生成流程，
不依据当前感觉修改已通过对照的随机公式。

## 参考版本与复跑工具

| 来源 | 本轮核对 | 结论范围 |
| --- | --- | --- |
| RWG | alpha-1.5.2 源码及项目已有反编译记录 | 原版 24 / 8 和 Forge 替换入口 |
| Village Names | 4.5.18-GTNH 源码和官方运行 JAR；4.5.10、4.5.16 选点源码 | 前三者候选规则一致；仅 4.5.18 执行了 JAR 对照 |
| Galacticraft | 3.4.33-GTNH 源码及项目已有 JAR；3.4.34、master 源码 | 选点源码相同；仅 3.4.33 执行了 JAR 对照 |
| GTNH 配置 | 官方仓库快照 83b3fb26cd9979e0256c7568bdf8c6f87579b532 | VN 启用、40 / 10 / 0；月球生成开关 false |

运行 JAR 的 SHA-256：

```text
VillageNames-4.5.18-GTNH.jar
7433fa393f513378bf359a8085a01044131b9f8eef20585c0c02041c1a235629
Galacticraft-3.4.33-GTNH.jar
5b29825bbfa51fa04c51afd1fa309e6cd603ea592de94c235bd7382eefada0e3
```

可复跑工具：`gtnh-worker/tools/AuditVillageSelectors.java`；用法见工具 README。
JAR 路径、受控 VN 间距和原点半径是输入，输出保留文件哈希，供其他版本复查。
工具只适配当前 SRG 运行 JAR 的类/方法接口；接口变化需要扩展适配器，不能静默跳过。
本次临时源码快照、对照程序和输出保留在忽略目录 `build/village-audit/`。

源码依据：

- [RWG 生成器与替换事件](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkGeneratorRealistic.java)
- [VN 4.5.18 村庄生成器](https://github.com/GTNewHorizons/VillageNames/blob/134dd42e784261bd60e5b5242e34ff9b53f666c9/src/main/java/astrotibs/villagenames/village/MapGenVillageVN.java)
- [VN 生成配置适配](https://github.com/GTNewHorizons/VillageNames/blob/134dd42e784261bd60e5b5242e34ff9b53f666c9/src/main/java/astrotibs/villagenames/config/village/VillageGeneratorConfigHandler.java)
- [GTNH 村庄配置快照](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/83b3fb26cd9979e0256c7568bdf8c6f87579b532/config/VillageNames4/newvillages/village_gen.cfg)
- [Galacticraft 月球选点](https://github.com/GTNewHorizons/Galacticraft/blob/26a472f9c7ae1f09e6d7149ae10aa4bbaa0e9e30/src/main/java/micdoodle8/mods/galacticraft/core/world/gen/MapGenVillageMoon.java)
- [Galacticraft 月球生成流程](https://github.com/GTNewHorizons/Galacticraft/blob/26a472f9c7ae1f09e6d7149ae10aa4bbaa0e9e30/src/main/java/micdoodle8/mods/galacticraft/core/world/gen/ChunkProviderMoon.java)
- [Galacticraft 配置定义](https://github.com/GTNewHorizons/Galacticraft/blob/26a472f9c7ae1f09e6d7149ae10aa4bbaa0e9e30/src/main/java/micdoodle8/mods/galacticraft/core/util/ConfigManagerCore.java)
- [GTNH Galacticraft 配置快照](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/83b3fb26cd9979e0256c7568bdf8c6f87579b532/config/Galacticraft/core.conf)
