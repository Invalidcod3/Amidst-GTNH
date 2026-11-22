# GTNH 2.9.0／2.9.1 植被分布与群系筛选

地图右下角原有的**生物群系筛选控件**新增“植被条件…”入口，支持 **RWG-Alpha 1.5.3** 和 **RWG2 2.0.6／2.0.9 大陆模式**。可搜索中英文名、模组名，按花朵、潘马斯菜园、潘马斯果树、树木分类，并选择“满足任一”或“满足全部”。应用后自动勾选匹配群系并开启高亮，原群系列表及其操作保持完整。完整逐项结果见[分布规则表](vegetation-catalog.md)。

## 如何理解结果

这里判断的是生成代码允许尝试的群系，不重放区块装饰，也不定位已经存在的植物。地面、空间、光照、高度、概率、结构占地及模组事件仍会使某次生成失败。树冠、花丛和偏移后的放置点可能跨群系边界，观察到植物不构成排除相邻群系的证据。

索引依据 **GTNH 2.9.0／2.9.1 默认配置**。当前 Worker 提供实际注册的群系名称和 Forge 标签，Viewer 用这些标签展开候选；群系数字 ID 可变化，不按固定原版 ID 匹配。该功能不读取用户修改后的植物配置、不验证存档方块、不把无结果解释成绝对不存在。未核实的条目保留说明，不编造分布。

主世界使用各自生成器的群系池；下界、暮色森林和罗斯 128b 的已收录规则也可筛选。其他维度暂不开启此入口。额外维度只收录已核实规则，不宣称穷尽各维度的全部装饰、结构植物、巨型蘑菇或人工育种树种。潘马斯的“作物”在这里指野生菜园及果树；成熟农作物本身不是按群系直接铺满地面的野生生成物。

## 潘马斯菜园

12 类均在配置中开启；主世界和暮色森林开启，维度 6 关闭。以下所有条件还须排除 `DEAD`。

| 菜园 | Forge 群系标签条件 |
| --- | --- |
| 浆果 | `FOREST`、`HILLS`、`COLD`、`WET` 任一 |
| 沙漠 | `SANDY` |
| 草本 | `PLAINS` 且非 `COLD` |
| 瓜类 | `PLAINS` 或 `FOREST` |
| 根茎 | `MOUNTAIN`、`HILLS`、`MESA`、`SAVANNA` 任一 |
| 香草、叶菜 | (`FOREST` 且非 `COLD`) 或 `WET` |
| 蘑菇 | (`FOREST` 且非 `COLD` 且非 `MOUNTAIN`) 或 `WET` |
| 茎秆 | (`PLAINS` 或 `FOREST`) 且非 `COLD`，或者 `WET` |
| 纺织 | `COLD`、`HILLS`、`WET` 任一 |
| 热带 | `WET` 或 `HOT` |
| 水生 | (`OCEAN` 或 `RIVER`) 且非 `COLD` |

例如 Meadow 的标签包含 `FOREST`、`PLAINS`、`LUSH`，能匹配多种温带菜园；“寒冷”不会统一排除所有菜园，浆果和纺织反而主动接受 `COLD`。水生菜园还必须找到能放置的水面。

这些标签表示模组注册的类型：`FOREST` 森林、`HILLS` 丘陵、`MOUNTAIN` 山地、`PLAINS` 平原、`WET` 湿润、`HOT` 炎热、`COLD` 寒冷、`DRY` 干旱、`SANDY` 沙地、`SAVANNA` 稀树草原、`MESA` 恶地、`OCEAN` 海洋、`RIVER` 河流、`DEAD` 死寂。不能只凭中文群系名猜测标签。

## 潘马斯果树

所有分支仍排除 `DEAD`。

| 分组 | 包含树种 | 条件 |
| --- | --- | --- |
| 温带 9 种 | 苹果、鳄梨、樱桃、栗子、肉豆蔻、梨、核桃、李子、醋栗 | `FOREST`，排除 `COLD`、`SPOOKY`、`MOUNTAIN` |
| 热带 25 种 | 香蕉、肉桂、椰子、椰枣、火龙果、木瓜、杏仁、杏、腰果、榴莲、无花果、葡萄柚、柠檬、青柠、芒果、橙子、纸皮、桃、碧根果、胡椒、柿子、开心果、石榴、杨桃、香草豆 | `WET` 或 `HOT`，同时排除 `PLAINS`、`DRY` |
| 椰子附加分支 | 椰子 | `OCEAN`；这条分支没有排除 `COLD` |
| 枫树 | 枫树 | 热带条件再加 `CONIFEROUS`（针叶林） |
| 橄榄 | 橄榄树 | 配置开启，但该版 `PamTreeGenerator` 没有对应自然生成分支 |

枫树判断实际嵌套在热带分支内，所以不能按“所有针叶林”筛选；普通寒冷针叶林并不自动满足条件。这里保留发货版本的实际行为。

## 花朵与其他树木

- **原版花和树**：RWG 的 `RealisticBiome*` 装饰器直接调用 `DecoFlowers`、树生成器；不照搬原版 1.7.10 群系表。BOP 支持群系调用自己的装饰器和加权花／树列表。河岸绿洲编辑还会在 Brushland、Heathland、Outback 增加花树，但需要足够的河流强度。
- **Biomes O' Plenty**：按当前生成器接入的群系类提取花朵列表、树叶种类、专门树生成器及开关。另检查树生成器的挂果参数：苹果见 Mountain、Orchard，柿子见 Dead Forest、Seasonal Forest，桃见 Cherry Blossom Grove、Orchard，梨见 Boreal Forest、Orchard、Woodland；仍须与所选生成器群系池取交集。矿工的喜悦按地下装饰处理；其匹配不意味着能在地表看到。巨型花单列。主世界、下界规则分别保存；BOP 接管的 Hell 会尝试生成燃烧之花。
- **Natura**：树木用精确英文群系名判断，并非 `FOREST` 等标签。例如红杉与桉树能在 `Meadow` 尝试生成；`Woodlands` 不等于 BOP 的 `Woodland`，`TemperateRainforest` 也不等于 `Temperate Rainforest`。许多旧名称在当前 RWG 群系池里没有匹配，不能自动替换成相似名称。蓝铃花没有群系类型限制，但有地面、光照和高度等放置限制。
- **Thaumcraft**：宏伟之木接受 `CONIFEROUS / FOREST / SAVANNA / PLAINS / SWAMP / LUSH / MAGICAL`；银树主要接受魔法群系，排除污染之地，魔法森林另有自身装饰分支。闪耀之花伴随成功生成的银树。烈焰花要求群系默认地表为原版沙、温度严格大于 1；RWG 的 `Hot Desert` 温度为 1，不能仅凭沙漠名称认定会生成。当前未给烈焰花填入未经核实的群系。
- **IndustrialCraft 2**：橡胶树配置开启，接受 `SWAMP / FOREST / JUNGLE`，仍须满足实际落地条件。
- **Et Futurum Requiem**：矢车菊接受 `PLAINS` 并排除 `SAVANNA / SNOWY`；铃兰接受 `FOREST` 并排除 `JUNGLE / DRY / HOT / SNOWY / COLD`。樱花树接受 `MOUNTAIN`，排除 `SNOWY / HOT / SANDY / MESA / SPARSE / JUNGLE / NETHER / END / DEAD / SPOOKY / WASTELAND`，配置尝试概率为每区块 1/72；粉红色花簇与樱花树关联。原版 Flower Forest 的额外矢车菊分支不在两种 RWG 主世界群系池内。
- **Botania**：16 色神秘花合并为一个筛选项。GTNH 的花朵维度白名单为 `64`，即罗斯 128b；不能据通用 Botania 描述把它们标到主世界。高花与普通花共享该维度条件。
- **暮色森林**：按自己的群系装饰器收录苍穹树、黑暗树、红树、彩虹橡树、原版木材树和花。结构中的树及特殊魔法树不套用普通树林规则。

同名植物按模组区分：BOP 樱花、Natura 樱花、Et Futurum 樱花与潘马斯樱桃果树是不同条目。RWG 的 Redwood 树形也不能等同于 Natura 红杉；分类按生成的方块材料识别。

Forestry 的育种品种没有按普通野生树林加入；已核对 `PluginArboriculture`、通用 `WorldGenerator`，它们不足以支持“全部树种按温湿度自然刷出”的推论。Witchery 的结构植物及梦境植物也不当作主世界通用野生花树条件。

## Alpha 与 RWG2 的兼容方式

分别读取 RWG-Alpha 的 `BaseBiomes`、`RealisticBiome*`、`SupportBOP`、`EditRiverOasis`，以及 RWG2 对应实现。两份生成器池独立保存，花树的直接生成器来源也区分版本。例子：RWG2 接入 Flower Field，而 Alpha 未接入；Alpha 接入 Mystic Grove，而本次核对的 RWG2 未接入。即使 Worker 注册表里存在这两个群系，也不会在另一种算法的筛选结果里混入。

该功能沿用地图现有群系筛选接口：应用时用匹配群系替换当前勾选项，并自动开启高亮，无需再逐个点选。原列表完整保留，不隐藏、合并或重新排列群系；仍可手动勾选任何其他群系。“全部”选择所有群系，“特殊”“清空”和高亮启用开关保留原有行为。同名的 BOP 原始／移植实例在应用时会勾选所有匹配编号，后续手动点选仍各自独立。无匹配时保持高亮模式且不勾选群系，避免误显示全部。条件窗口中的“清除高亮”关闭高亮；切换维度也会关闭本次植被高亮。关闭对话框或取消不会应用草稿。重新打开条件窗口保留已应用植物；搜索、分类切换也保留选中项，可用“清空所选植物”重选。

## 数据与复现

2026-11-22 已核对 GTNH 2.9.1 本地实例与官方版本差异，已收录植物的群系准入规则不变。分别用 RWG2 2.0.6 与 2.0.9 源码生成的 150 个条目和群系池完全一致，共用随包目录；核验范围见 [2.9.1 适配记录](gtnh-2.9.1.md)。下列 2.9.0 来源保留为最初规则基准。

默认配置基准为官方 [GTNH 2.9.0 标签](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/tree/2.9.0)，对应提交 `79f9a03b5dd9e492d28998b9dee0ea1d1822a6b1`。参考 [HarvestCraft 配置](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/2.9.0/config/harvestcraft.cfg)、[Natura 配置](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/2.9.0/config/Natura.cfg)、[Botania 配置](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/2.9.0/config/Botania.cfg)。

规则依据本地 2.9.0 随包 JAR 的实际生成方法及可用源码。版本：HarvestCraft 1.3.15-GTNH、BOP 2.1.0.2308、Natura 2.8.24、Thaumcraft 4.2.3.5、IC2 2.2.828、Botania 1.13.42、Twilight Forest 2.7.45、Et Futurum 2.6.65。配置决定启用状态，生成方法决定标签、名称、布尔优先级和维度条件。

| 核对 JAR | SHA-256 |
| --- | --- |
| HarvestCraft 1.3.15-GTNH | `C5F5C10BCA3448C65749B7BD5ADB9FA803D40CABC4E07FD3A401A01EFF3AC732` |
| RWG-Alpha 1.5.3 | `FB5EE6C9927729C7DC82967542205084BD54BC032139502C69967B07268925FD` |
| RWG2 2.0.6 | `19CC1E21238CF28253225963A24980519C747A0663A61AE8AE489A19C86E178B` |
| BOP 2.1.0.2308 | `5382D1C156C871C896B6CEBD036B80F58CEAFF36B09D416B113F32884B7B4A2A` |

应用随包目录为 `src/main/resources/amidst/gtnh/vegetation.json`，每条规则保存 `source` 来源。名称规则和标签规则分开保存；标签表达式采用“任一子句成立，子句内所有条件成立”，明确保留否定项。未引入 Worker 协议变更。

重建工具：`node tools/Generate-VegetationCatalog.mjs [audit目录] [RWG2源码根目录]`。默认目录分别为忽略的 `build/vegetation-audit` 与 `build/rwg2-reference/sources/rwg2`。需要按上述版本准备以下输入：

- `alpha/rwg`：Alpha 的 biomes、support 源码；`bop/biomesoplenty`：BOP 的 API、注册和群系／装饰器源码；`tf/twilightforest/biomes`：暮色森林群系源码。
- `BiomesOPlenty-en_US.lang`、`BiomesOPlenty-zh_CN.lang`、`harvestcraft-en_US.lang`、`harvestcraft-zh_CN.lang`：从对应 JAR 提取的语言文件。
- HarvestCraft、Natura、Thaumcraft、IC2、Botania、Et Futurum 的标签／维度条件经人工核对写入工具；不能只更换输入文件便宣称支持新整包版本。

反编译审计使用 CFR 0.152，Natura 使用该版源码包，RWG2 使用对应源码。游戏二进制、反编译全文、存档、日志和连接信息不进入版本库。生成工具同时更新规则 JSON 和逐项分布表。测试覆盖标签优先级、果树特殊分支、Et Futurum 排除项、生成器池隔离、维度及扩展 ID、多选交并集、搜索保留选择与高亮清除；这些测试不能替代实地逐种植物生成验收。
