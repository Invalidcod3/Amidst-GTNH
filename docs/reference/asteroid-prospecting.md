# 小行星探矿规则与精度

本次以 GTNewHorizons/GT5-Unofficial 的 `5.09.54.133` 标签源码为依据，
对应当前项目参考的 GregTech 版本。算法核对直接阅读官方 Java 源码。

## 修复内容与使用

- Asteroids、KuiperBelt、MehenBelt 使用小行星生成规则，逐区块寻找候选，
  不再经过普通矿脉的 `isOreChunk` 网格及矿脉概率筛选。
- 矿种列表区分“混合矿石小行星”和“小矿石小行星”，按运行时维度、岩石类别、
  矿种注册及权重筛选；没有硬编码矿种列表。
- 地图标点为生成器选出的 X/Y/Z 中心。圆形标记提示小行星候选，
  不是普通矿脉的方形区块，也不是精确的三维形状投影。
- 双击添加、普通导出、按距离导出和 JourneyMap 文件保留已知中心 Y。
  有高度的坐标 CSV 使用 `x,z,y`；全部高度未知的旧坐标仍使用 `x,z`。
- 末地按游戏自己的有效维度规则划分岛屿与虚空。请先进入对应存档的末地，
  使 HEE 的岛屿布局可用，再查询末地矿脉；未加载时探矿提示加载，
  稀有小行星结构图层暂不显示候选。末地加载/卸载会使 Viewer 重新查询。
  主菜单下仍可预测上述三个独立小行星维度。
- 末地原有稀有小行星图层与探矿共用当前版本的选择器，避免两套位置算法漂移。

当前安装与协议要求见[发布说明](../release-v0.3.1.md)。

## 官方源码依据

以下链接固定到同一标签，维护时应同时检查，不能只移植其中一段随机数公式。

1. [WorldGeneratorSpace.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/galacticgreg/WorldGeneratorSpace.java)：
   `generate → AsteroidGenerator.forChunk → generateChunk`。
   随机种子是 `cx * 341873128712L + cz * 132897987541L + dimensionId + 588283L + seed`；
   概率接受条件是 `nextInt(100) <= Probability`，因此概率配置为零仍接受随机值零。
   概率判断后重新初始化随机数；岩石选择使用副本，主随机数 `nextInt(5) == 0`
   选择小矿石，其余选择混合矿石，矿种查询再次使用副本。
   随后依次生成 X、Y、Z、半径；Y 上界不包含，半径上界包含。
2. [DimensionDef.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/galacticgreg/api/enums/DimensionDef.java)
   与 [ModDimensionDef.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/galacticgreg/api/ModDimensionDef.java)：
   确定维度类别和启用开关。末地主岛附近、HEE 岛屿附近、混沌岛附近使用 TheEnd，
   其余使用虚拟定义 EndAsteroids，仍然属于真实的末地维度 ID 1。
3. [WorldgenQuery.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/gregtech/common/worldgen/WorldgenQuery.java)：
   `small/veins → inDimension → inStone → findRandom`，运行时反射调用这条原生查询链。
4. [HEEIslandScanner.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/gregtech/common/worldgen/HEEIslandScanner.java)
   与 [ChaosIslandLocator.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/gregtech/common/worldgen/ChaosIslandLocator.java)：
   末地分区依赖。HEE 查询访问当前已加载末地及其岛屿/龙状态，不能把另一个存档
   的全局末地对象用于任意种子的预测，也不能用 Viewer 的群系绘图半径代替生成判断。
5. [DynamicDimensionConfig.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/galacticgreg/dynconfig/DynamicDimensionConfig.java)
   与 [XSTR.java](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/gregtech/api/objects/XSTR.java)：
   配置和随机数语义。

## 代码维护入口

- `AsteroidOrePredictor`：无世界访问的随机数前缀，保留原生成器的调用顺序和副本语义。
- `AsteroidProspecting`：原生配置、有效维度、岩石/矿种查询与展示数据适配；不调用生成器放置方块。
- `ProspectingService`：在普通矿脉网格判断之前选择生成模型；普通矿脉和流体路径保留。
- `EndAsteroidPredictor`：当前 GTNH 分支复用上述适配器；旧版 GT 算法分支保留，未纳入本次新算法验证。
- 两端 `ProspectingData`：`kind` 区分小行星类别，`y` 为可空中心高度。
  结构数据也传递可空 `y`，一路传至 `WorldIcon`、坐标导出和 JourneyMap。
- `BiomeWorkerServer`：末地世界对象改变时更新会话，使旧查询和空结果失效。

## 验证与边界

`AsteroidOrePredictorTest` 使用直接编译官方 XSTR 源码生成的固定对照数据，
覆盖不同种子、维度 ID、负区块、概率边界、小矿石分支、随机数副本及禁用配置。
导出、结构图层与通信测试检查实际 X/Z、中心 Y、维度以及未知高度的兼容行为。
普通矿脉、流体、缓存等已有回归测试一起运行；发布构建检查协议、共享数据类及 Worker SRG 映射。

这些检查证明种子选择及数据传递规则，不替代完整 GTNH 存档实测。
小行星的布尔椭球形状、实际空气方块、重叠生成和配置决定最终放置结果，
候选中心不保证该位置恰好存在矿石方块；显示的高度范围只是生成器的保守包围范围。
普通 Visual Prospecting 矿脉记录无法证明小行星的形状或小矿石存在，
因此“仅已记录”不会包含预测小行星，准确性验证将候选标为“无法验证”，不算匹配。

