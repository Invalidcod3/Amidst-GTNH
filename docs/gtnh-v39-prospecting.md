# v39：Structure / Ores / Fluid 探矿图层

## 使用

安装配套的 v39 Viewer 和 Worker；协议升级到 **19**，不能与 v38 Worker 混用。
关闭 GTNH 后替换 `mods` 中的旧 Worker，再重启游戏。本轮针对本机 GTNH
2.9.0-beta-3、GregTech 5.09.54.133、Visual Prospecting 1.5.39 的运行时接口开发。
新增探矿模块依赖这些版本的 GT WorldgenQuery 与 Visual Prospecting API；未验证 2.8 系列。

在 `Layers → Dimension` 下方的 `Markers` 选择：

- **Structure**：原有定位功能和各结构开关。
- **Ores**：矿脉图标。放大显示名称；悬停显示矿脉名、组成矿物、Y 范围、X/Z
  坐标与数据来源。点击图标复制坐标。虚线框为种子候选，绿色实线框为 VP 服务端记录。
- **Fluid**：调暗群系底图，以 VP 的颜色显示流体田。放大后显示区块网格、各区块产出，
  金色边框标出田内产出最高且不低于 10 L/Op 的区块。每个流体田为 8×8 区块。

`Filter Ores/Fluid` 按矿脉、矿物或流体名称筛选，留空恢复全部。
切换模式会清空筛选。`Refresh prospecting` 重新读取可见区域；普通缓存每 30 秒刷新。
`World → Export Coordinates` 新增每维度的 `Ore veins (all)` 与
`Underground fluids (all)`，沿用原有坐标导出和 JourneyMap 导入流程。
导出的矿脉点为中心区块中心，流体点为流体田西北角；条目名称保留数据来源。

## 数值与精度

Visual Prospecting 显示的 **L/Op 是每次操作的流体产出量，不是总可采储量**。
本实现沿用该单位。区块边长达到 58 屏幕像素后才显示逐块数值；更远处显示颜色、流体名，
中等距离显示田内最小/最大产出。Viewer 最大放大范围已相应扩大。

矿脉先读取同种子存档的 `ServerCache.getOreVein`；未记录时按 GT 的中心区块规则、
维度生成概率、FNV 与 XSTR 随机序列执行第一次加权选择。`EMPTY_VEIN` 是“尚未记录”的哨兵，
不能当成已证实的空矿脉。实际记录的 `NO_VEIN` 则不显示。

**种子候选不是已确认矿脉。** 真实放置受高度、地形、岩石类别与失败后的重试影响；
尤其末地和小行星维度，不能把规则网格上的候选当作已有岛屿/小行星中的确定矿脉。
已生成矿脉由 VP 服务端记录覆盖。客户端独有的手动耗尽标记与筛选不会写回或改变游戏。
本轮未重放全地形放置，也不会为了探矿加载/生成区块。

流体初始值重放 `UndergroundOil.getPristineAmount`，按原 X-major 顺序一次计算整个田的
64 个区块。若当前同种子维度已加载，且 GT 的内存储量对象已经存在，则读取其当前量。
这类区块的悬停信息显示 `current`；其他区块显示 `initial`。
不会为了获取剩余量加载磁盘区域，也不会把未读到的剩余量宣称为实时量。
查询不调用 `undergroundOil(...,-1)`：该“读取”分支也可能将低产区块置零。

## 维度和图标

Viewer 共可映射 42 个 GT 维度定义，`EndAsteroids` 与 `TheEnd` 共用末地入口。
Worker 从 Forge 注册表和实际 Provider/CelestialBody 获取运行时维度 ID，再匹配
GregTech 的 `DimensionDef`、VP 矿脉目录和地下流体配置。菜单只加入当前运行时可用的新增维度。
新增 24 个入口包含 Everglades、Ross 128ba、Triton、Oberon、Titan、Callisto、Ganymede、
Deimos、Europa、Phobos、Venus、Mercury、Makemake、Haumea、Alpha Centauri Bb、Vega B、
Barnarda E/F、Tau Ceti E、Miranda、Kuiper Belt、Neper、Maahes、Seth。

原有 18 个维度保留群系底图。新增维度在 `More dimensions` 中提供探矿地图，
**尚无已验证的群系底图**，界面明确提示此状态；不会套用主世界群系。
这些维度的本地枚举 ID 仅用于保存界面选择，实际查询及 JourneyMap 导入使用 Worker 提供的 ID。

矿脉图标复用 VP 的纹理读取方法和 `DimensionStoneBackground`：维度岩石底图叠加矿物
代表纹理、颜色与覆盖纹理，不受游戏中当前搜索高亮状态影响。维度图标使用 GTNEIOrePlugin
维度标记方块的原始 top/front/right 贴图组合成立体小图；从正在运行的模组读取，不打包分发
整个模组。菜单 Ores/Fluid 按钮来自 Visual Prospecting 1.5.39，MIT 许可副本位于
`src/main/resources/licenses/VisualProspecting-LICENSE.txt`。

## 验证与维护

- `InstalledProspectingTest` 提取指定 GregTech JAR 中的真实 `getPristineAmount` 方法，
  替换周边世界/配置对象后执行，与批量路径逐点比较 7,680 个区块；覆盖五种种子、四个维度 ID、
  六组正负边界区域。算法主体来自实际 JAR，不复制为期望实现。
- `ProspectingOverlayTest` 检查查询不在 Swing 线程运行、缓存复用、模式切换、筛选、维度键唯一；
  将模拟数据渲染到 `build/reports/prospecting/`，用于检查缩放和排版。图中模拟数据不是游戏实测。
- 原有网络、暂停游戏调度、RWG/BOP、罗斯群系规则和坐标导出测试一起执行。
- 安装 JAR 经过 SRG 映射及开发 JAR 拒绝检查。
- 尚未安装到正在运行的 GTNH，完整游戏中的探矿目录、图标和实际地图对照需要更新两端后复测。

构建：

```powershell
.\gradlew.bat assembleRelease '-PrwgReferenceJar=build/biome-accuracy-reference/RWG-alpha-1.5.2.jar' '-PgregtechReferenceJar=build/biome-accuracy-reference/gregtech-5.09.54.133.jar' --offline --console=plain
```

`ProspectingService` 负责运行时目录、记录读取与分段预测；`ProspectingTextures` 负责运行时图像；
`ProspectingData` 是独立构建两端各一份的相同传输类型；修改时须同步。
`ProspectingOverlay` 负责单请求异步加载、256 瓦片 LRU、30 秒刷新及屏幕坐标绘制。
请求限 512×512 方块，前台交互每次请求 256×256 方块；Worker 接入已有游戏 tick 软预算。
远景超过 256 个可见瓦片时提示放大，避免无界请求。

参考源码：

- [Visual Prospecting 1.5.39](https://github.com/GTNewHorizons/VisualProspecting/tree/1.5.39)
- [GregTech UndergroundOil 5.09.54.133](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/gregtech/common/UndergroundOil.java)
- [GregTech DimensionDef](https://github.com/GTNewHorizons/GT5-Unofficial/blob/5.09.54.133/src/main/java/galacticgreg/api/enums/DimensionDef.java)
