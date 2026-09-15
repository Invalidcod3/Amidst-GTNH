# v41：补齐可用探矿维度和维度图标

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

后续更新已补齐额外维度的群系底图，见 [更多维度群系说明](../reference/additional-dimension-biomes.md)。下文保留 v41 的原始行为记录。

## 使用

替换为配套 `amidst-gtnh-worker-v41.jar` 与 `amidst-gtnh-biomes-v0-2-v41.jar`，
重启 GTNH。协议仍为 20。维度列表位于 `Layers → Dimension`，
其余探矿入口位于 `More dimensions (ores / fluids)`，每项前显示整合包的维度方块图标。
悬停可查看是否支持矿脉、地下流体。当前维度不支持的标点模式会禁用。

仅保留有矿脉、地下流体或已支持 POI 的维度，没有这三类内容的不会加入。
同样的规则用于坐标导出；导出类型列表不再包含该维度不支持的矿脉/流体条目。
下拉筛选、最低 L/Op 和点击区块中心复制坐标继续沿用 v40。

## 维度范围

针对本机 GTNH 2.9.0-beta-3 / GregTech 5.09.54.133 核对了完整注册表。
GT 有 43 个维度生成定义，其中 `EndAsteroids` 与 `TheEnd` 共用同一个实际维度，
Viewer 的 42 个入口覆盖其余全部定义，未遗漏当前版本的 GT 星系维度。

以前隐藏的入口包括废土世界（Everglades）、Ross 128ba、Triton、Oberon、Titan、Callisto、
Ganymede、Deimos、Europa、Phobos、Venus、Mercury、Makemake、Haumea、Alpha Centauri Bb、
Vega B、Barnarda E/F、Tau Ceti E、Miranda、Kuiper Belt、Neper、Maahes、Seth。
最终显示与当前整合包配置的资源和 POI 可用性一致，不把空维度或没有世界的装饰天体加入菜单。
不需要先进入每个星球；实际维度 ID 从运行时注册表取得，兼容修改过 ID 的配置。

这些入口沿用 v39 的探矿底图能力：原有群系底图保持，额外探矿维度仍无已验证的群系背景。
未记录的矿脉是种子候选，流体按可取得的数据区分 current / initial；精度说明见 v39 文档。

## 修复原因与验证

旧代码调用 `GTUODimensionList.GetDimension(id)`。该方法对于按 Provider 类名配置的维度，
会取得 `DimensionManager.getProvider(id)`；维度世界尚未加载时返回 null，随后抛出异常。
整个维度因此被排除，矿脉入口和图标也一起丢失。游戏日志确认异常来自这一调用。

新路径直接读取 GT 地下流体配置：先检查禁用 ID，再匹配数字 ID、Provider 完整类名子串，
最后采用 Default；保持 GT 的优先顺序和大小写规则。流体查询复用解析出的配置。
不会初始化世界、生成区块、注册游戏维度或改变流体储量。
维度发现合并 Forge Provider 注册表及 Galacticraft 的行星、卫星目录。
图标读取失败也不会使有效维度从目录消失。

- `InstalledOilDimensionTest` 提取真实 GT 方法，复现未加载世界的异常，并对照修复后的已加载行为。
- `ProspectingDimensionsTest` 验证未加载星球发现、自定义 ID、禁用项、配置优先顺序及大小写。
- `InstalledDimensionIconsTest` 从真实 GT JAR 提取 43 个维度图标注册项，逐一验证三面贴图并生成预览。
- `AuditProspectingDimensions.java` 将真实 GT 枚举与发布 Viewer 的 42 个维度键逐项比较。
- `ProspectingCatalogTest` 验证空维度排除、POI 维度保留，以及流体专属维度的导出类型。
- 完整构建同时运行现有测试及 7,680 个流体区块算法对比，并检查 Worker 安装映射。

本轮未替换游戏中的模组，也未重启游戏。新包在完整游戏中的菜单和点位需安装后复测。
