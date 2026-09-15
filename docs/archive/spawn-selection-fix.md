# 出生点实例调查与选中信息修复

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

调查种子：`-8138049151491905853`，GTNH 2.9.0-beta-3，Worker 协议 25。

后续改进见 [出生点洞穴重放](spawn-cave-replay.md)。下文记录本补丁发布时的状态；新补丁需要更新 Worker。

## 实测结果

| 数据来源 | 坐标或结果 |
| --- | --- |
| 截图左上角的旧选中项 | Estimated world spawn `(-336, -573)` |
| 运行中的 Worker `spawn` 回复 | `(-58, 64, -180)`，`RECORDED` |
| 保存退出后的 `level.dat` | 同一种子，SpawnX=-58，SpawnY=64，SpawnZ=-180 |
| 截图玩家站立位置 | 约 `(-68, 70, -177)` |
| 存档 `(-58,-180)` 地表列 | Y=63/64/65 为 minecraft:stone，Y=66/67 为空气 |

握手确认提供器是 `biomesoplenty.common.world.WorldProviderSurfaceBOP`，
群系管理器是 `rwg.world.ChunkManagerRealistic`。不是另一个种子的存档，也不是坐标缩放问题。
调查只读取 Worker 和复制到工作目录的存档文件；没有生成、改动或替换游戏存档。

## 已修复：左上角仍显示旧估算位置

地图与跳转已经使用新的存档坐标，但 `WorldIconSelection` 保存了最初选中的不可变图标，
`SelectedIconWidget` 又一直读取这个旧对象。因此地图更新后，信息框仍可显示旧名称和旧坐标。

现在出生点图标有明确的 `GtnhSpawnIcon` 类型。选中信息通过当前世界的出生点快照更新，
不根据翻译后的名称或图标图片猜类型。存档位置到达后，信息框随之变为“存档世界出生点”；
当前快照失效时隐藏旧坐标，后续新快照恢复显示。普通结构的选中行为保留。

测试使用本次报告的两组坐标，覆盖信息框文本更新、选中高亮、失效、恢复及普通结构不受影响。

## 仍存在：纯种子估算与游戏选址不同

按 `Random(seed)`、RWG 空初始群系搜索及原版的 X/Z 移动顺序计算：

- 第 71 次移动到 `(-58,-180)`，等于存档世界出生点。
- 第 201 次移动到 `(-336,-573)`，等于截图的估算位置。

这把差异定位在选址接受条件及其地表输入，而非简单的随机数步长或固定偏移。
当前存档该处的石头符合 BOP 的规则，但这里只观察到了生成后的方块，
没有记录创建世界时每一个阶段的地表，不能由此证明具体是哪一个回调改变了方块。

源码确认当时的预览只重放到 RWG 地表替换阶段；完整游戏随后还执行洞穴、结构和区块地物覆盖。
这些阶段会影响 `World.getTopBlock` 所读到的方块。当前证据足以确认估算与实际地表不一致，
尚不足以在洞穴、地物、事件回调或其他重放差异之间作出唯一归因。
因此本次**修复选中信息，不宣称修复了所有纯种子精确预测**。已加载存档的世界出生点读取已由本实例实测确认。

要精确修正估算，需要进一步记录同一位置在“原生地表替换后、洞穴后、地物覆盖后”的判定输入，
并比较首次被接受的位置；不能根据这个种子添加偏移量，也不能以加载后的方块取代未生成世界的独立预测来伪造通过。

源码依据：

- [GTNH RWG alpha-1.5.2 / ChunkManagerRealistic](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkManagerRealistic.java)
- [GTNH RWG alpha-1.5.2 / ChunkGeneratorRealistic](https://github.com/GTNewHorizons/Realistic-World-Gen/blob/alpha-1.5.2/src/main/java/rwg/world/ChunkGeneratorRealistic.java)
- [BOP 1.7.10 官方提供器](https://github.com/Glitchfiend/BiomesOPlenty/blob/fc7f1f6392a16224d19538900acedd152ff4f350/src/main/java/biomesoplenty/common/world/WorldProviderSurfaceBOP.java)
- 原版/Forge 源码调用链见 [出生点修复说明](spawn-fix.md)。

## 安装

本补丁协议仍为 **25**，Worker 内容与上一个出生点补丁相同。
已经安装上一个协议 25 补丁的用户，只需关闭 Viewer，换用此包中的 Viewer JAR；无须再次替换 Worker。
从更旧的包升级时，两端都需要更新并重启游戏。

包名：`Amidst-GTNH-v0.3.1-spawn-selection-fix.zip`，包含此前全部修复及配套源码归档。
构建后使用：

```powershell
.\tools\Package-Release.ps1 -PackageSuffix spawn-selection-fix -ReleaseNotes docs/spawn-selection-fix.md
```
