# 更多维度的生物群系底图

本次补齐“更多维度”中 24 个入口的群系采样与显示，并将 Everglades 的中文名称统一为“废土世界”。
当前安装与协议要求见[发布说明](../release-v0.3.1.md)。

## 采样依据

核对了本地参考包 GalaxySpace 1.1.142-GTNH、GregTech 5.09.54.133、AmunRa 0.8.14
的 Provider 和 WorldChunkManager 字节码；并对照 GTNH 2.8.0 中的相应实现。

| 维度 | 游戏中的群系来源 |
| --- | --- |
| 废土世界（Everglades） | `WorldChunkManagerCustom(seed, WorldType.LARGE_BIOMES)`，使用模组自己的 GenLayer 与最终方块坐标查询 |
| Ross 128ba | Galacticraft `BiomeGenBaseMoon.moonFlat` |
| Titan | `BiomeGenTitan.INSTANCE` |
| Venus | `BiomeGenVenus.INSTANCE` |
| Kuiper Belt | `BiomeGenBaseKuiper.INSTANCE` |
| Neper、Maahes、Seth | AmunRa 群系管理器返回 Galacticraft `BiomeGenBaseOrbit.space` |
| Triton、Oberon、Callisto、Ganymede、Deimos、Europa、Phobos、Mercury、Makemake、Haumea、Centauri Bb、Vega B、Barnarda E/F、Tau Ceti E、Miranda | GalaxySpace `WorldChunkManagerSpaceGS` 返回 `GSBiomeGenBase.SPACE` |

除废土世界外，上表这些管理器实际返回单一群系，因此整片同色是正常结果。
单一群系不代表地表只有一种方块；此处显示群系底图，不模拟每个星球的逐方块地形。

未加载区域直接按种子查询原生群系管理器或使用真实单群系对象，不创建世界或地形区块。
同一种子的已加载区块优先使用游戏中的群系数据。废土世界固定使用其 Provider 指定的
LARGE_BIOMES，不受 Viewer 的主世界类型选项影响。

## 显示与坐标

- Worker 报告各维度的群系支持情况，Viewer 按实际能力启用底图，移除原先对全部额外维度的禁用。
- 矿脉模式保留群系底图，流体模式沿用半透明调暗；支持底图的维度也可切换到结构模式查看纯底图。
- 光标显示真实群系名称。单群系星球沿用模组注册名称，不虚构额外群系。
- 查询使用运行时维度 ID 和该维度的方块坐标；额外维度的本地枚举 ID 只保存界面选择。
- 对星球局部群系使用独立显示 ID，避免相同原始 ID 在切换维度后串名或串色。
- 若模组缺失或群系初始化失败，保留探矿入口和明确的底图不可用提示。

回归测试覆盖 24 个额外维度的支持声明、运行时 ID、负坐标、4×4 采样、种子切换与缓存淘汰、
显示 ID 隔离，以及矿脉覆盖层保留底图。构建验证 Worker 安装包的 Minecraft 映射。
未替换用户游戏中的模组，完整游戏内显示仍需安装配套包后确认。
