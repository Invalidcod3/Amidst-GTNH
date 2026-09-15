# 冥王星生物群系与地表配色

## 核对结果

已核对本地 `GalaxySpace-1.1.142-GTNH.jar` 参考包，以及 GTNH 2.8.0 使用的
`GalaxySpace-1.1.121-GTNH.jar`，并交叉检查 GTNH 2.7.4 的 `GalaxySpace-1.1.99-GTNH.jar`
中四张地表材质的颜色，三版相同。

- `galaxyspace.SolarSystem.planets.pluto.world.BiomeGenPluto`：四个群系使用
  `GSBlocks.PlutoBlocks` 作为地表，`PLUTO_1`～`PLUTO_4` 的 `topMeta` 分别为 0～3。
  群系注册名称依次为 `Pluto`、`Pluto2`、`Pluto3`、`Pluto4`。
- `ChunkProviderPluto.enableBiomeGenBaseBlock()` 返回 true；父类
  `galaxyspace.core.dimension.ChunkProviderSpaceCraters` 在地表生成中读取群系的
  `topBlock` 和 `topMeta`。因此群系确实决定通常地表的方块变体，而不只是名称和地形高度。
- `galaxyspace.core.register.GSBlocks` 将这四个 metadata 按顺序映射到下表材质；
  `BlockTerraformableMeta.getIcon(side, metadata)` 使用相应材质，各面相同。

## Viewer 默认颜色

旧规则将所有 `Pluto*` 群系画成 `#A7B7C2`，仅按显示 ID 增减少量亮度。
新规则使用对应地表材质的平均 RGB，不再按 ID 改色。

| 群系 | 地表 metadata | 材质文件 | 预览颜色 |
| --- | ---: | --- | --- |
| Pluto | 0 | plutogrunt.png | `#A57E61` 棕褐色 |
| Pluto2 | 1 | plutogrunt2.png | `#F9F0D6` 浅乳白色 |
| Pluto3 | 2 | plutogrunt3.png | `#C3B9AF` 灰米色 |
| Pluto4 | 3 | plutogrunt4.png | `#8C5235` 红棕色 |

材质位于 JAR 的 `assets/galaxyspace/textures/blocks/pluto/`，均为 16×16 PNG。
计算方法：逐像素按 alpha 加权，对 R/G/B 分别求平均并四舍五入到整数。
四张原始材质均不透明，因此等价于全部 256 个像素的通道算术平均。

颜色在 Viewer 的 `GtnhBiomeColorPalette` 中按群系名称匹配，兼容 Worker 对群系 ID
进行维度隔离及整合包自定义 ID。显式提供的颜色文件和用户选择的群系配色方案仍可覆盖默认颜色。

这是与默认材质对应的生物群系颜色预览，不是逐方块地表渲染；不模拟光照、阴影、
陨石坑露出的底层、矿石或玩家修改的地表。资源包替换材质后，可通过颜色覆盖文件调整。
配色修改本身不改变冥王星群系分布；当前安装与协议要求统一见[发布说明](../release-v0.3.1.md)。

验证包括四种材质颜色、不同注册/显示 ID 下的颜色稳定性、用户覆盖优先级及完整 Viewer 测试。
