# GTNH 2.9.1 适配依据

核对日期：2026-11-22。基准为本地 PrismLauncher 的 GTNH 2.9.1 实例、官方 [整包变更](https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/master/releases/changelogs/changelog%20from%202.9.0%20to%202.9.1.md)、[RWG2 2.0.6 → 2.0.9 源码差异](https://github.com/GTNewHorizons/Realistic-World-Gen-2/compare/2.0.6...2.0.9)及随版本发布的源码包。
RWG2 2.0.9 提交为 `51cbb3b5138148861c06c87581e06932327cb599`；本地运行 JAR SHA-256：`72b9b0c11ac21620311214389c6ad53ca40771e0c22b23be82546a572a91049d`。

## 地形与线程

`Rwg2Version` 按原始 `ChunkManagerRealistic` 字节码选定整个构建的核验清单，分别保留 2.0.6 与 2.0.9 的签名，不能混合接受不同版本的类。`Rwg2ParallelSupport` 核验完整清单及实际注册的回调，核验失败时保留游戏线程执行。

2.0.9 的管理器构造重放新增了 `ContinentalConfig.biomeSizeMultiplier`、`ClimateBandConfig` 和污染之地开关。气候宽度为 `1400f * multiplier`，群系宽度为 `500f * multiplier`；普通扭曲强度为气候宽度的 `0.8f`，启用气候带后按配置替换。每大陆污染之地启用时，从各气候的共享小群系池副本移除同一污染群系实例，保留原生每大陆放置路径；不修改游戏的 Support 列表。

生成器和 ContinentalNoise 的实际计算仍来自选定版本的模组。新版河流雕刻改为回调接口，独立副本映射到公开的 Worker 接口，保留原生循环和水／空气高度。新版熔岩洞室通风修正沿用实际 `LavaCaveLandmark.generate`。所有暂存方块、噪声与管理器均归独占采样上下文。

`SettingsConfig` 替代旧 `ConfigRWG` 的生成开关，包括出生点／地牢用的洞穴，以及村庄、矿井、地下湖。旧版本继续读旧配置类。RWG2 的存储名改为 `rwg2_data`，Worker 只读已初始化的噪声模式字段，不调用会迁移文件或标记数据修改的 getter。

磁盘瓦片算法身份从 `map-v26` 改为 `map-v27`；身份仍包含模组、配置及存档。已有区块的实际群系与未生成区域的当前算法预测保持区分。

## 河流地标与暮色水池

2.0.9 直接调用已审计的 `RiverJunctionLandmark.findCenter`、`pickCenterpiece`、`hasStrongerCenter`。中心必须属于当前区块；去重按原生 20 区块半径、强度与坐标平手规则处理。独立生成器的 `hasOpenJunctionWater` 重放河流雕刻后的高度条件，不借用真实世界。

默认传送门概率 `0.25`、距 **(0,0)** 的最小距离 `3500`、未激活概率 `0.5`，均读取运行时 `LandmarkDecorationsConfig`。它们不是距玩家或存档出生点的距离。新区间使用原生 24 位随机位段；旧版仍用原有两位判定。

未激活水池保留协议种类 `RWG2_TWILIGHT_PORTAL`，子类型为 `RWG2_RIVER_CHAMBER_UNLIT`；Viewer 采用原版 `flower_dandelion.png` 纹理并明确名称。已激活传送门继续用虞美人。二者在同一图层查询、导出，不增加重复请求。水池后来被玩家激活时，已加载中心的传送门方块证据会将标记更新为已记录的激活传送门；仅有水不升级确定性。

## 植被

[官方整包配置差异](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/compare/2.9.0...2.9.1)中，相关生成配置变更集中在 `rwg2.cfg`；已收录植物的准入配置不变。HarvestCraft 1.3.15、BOP 2.1.0.2308、Natura 2.8.24、Thaumcraft 4.2.3.5、RWG-Alpha 1.5.3 版本不变。对比实际 JAR，Botania 1.13.44、Forestry 4.11.41 与 Twilight Forest 2.7.47 的相关自然生成代码不变。

Et Futurum 2.6.66 的世界生成入口增加了沉船和废弃传送门，花朵、樱花等群系准入条件不变；见 [Et Futurum 差异](https://github.com/GTNewHorizons/Et-Futurum-Requiem/compare/2.6.65-GTNH...2.6.66-GTNH)。新结构没有在本版新增定位图层。

分别用 2.0.6 与 2.0.9 源码重建植被目录，150 个条目的 `entries` 和两个生成器的 `eligible` 集合逐项一致。因此共用 `vegetation.json`，标注 GTNH 2.9.0／2.9.1；仍只按默认植被配置判断群系准入，不定位植物。

## 复现

`InstalledRwg2ReferenceTest` 根据提供的 JAR 识别参考版本；测试只做 SRG → MCP 名称转换，不用 Worker 实现替代原生的地形方法。新版注解默认值从原始字节码读取到独立类加载器，不注册或写入游戏配置。

用 [BUILDING.md](../../BUILDING.md) 的参考参数分别运行 2.0.6 与 2.0.9。覆盖：两种噪声、正负种子和坐标、最终群系及地下方块、洞室水面判断、出生点、共享静态状态不变、并行上下文隔离；新版另以 20,000 个群系点核验倍率、气候带、偏移及污染之地配置。传送门测试检查距离边界、禁用概率与全部未激活／已激活两种极值。

纯 JVM 验证不包含完整客户端的模组启动、实际区块装饰及手动地图操作；这些仍需完整游戏验收。
