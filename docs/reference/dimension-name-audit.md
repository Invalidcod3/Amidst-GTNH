# GTNH 维度名称汉化核对

核对日期：2026-09-15。逐项核对 Viewer 的全部 42 个维度入口，修正 12 项显示名称，其余 30 项一致。

## 对照来源

- [2026-09-15 每日发行包](https://github.com/Kiwi233/Translation-of-GTNH/releases/tag/0-nightly-build/2026-09-15)：本次采用的译名。
- [2.8.4 发行包](https://github.com/Kiwi233/Translation-of-GTNH/releases/tag/2.8.4)：交叉核对；项目将该版说明为“汉化未审核版本完成”。

主要依据包内 `config/txloader/forceload/GregTech[gtneioreplugin]/lang/zh_CN.lang` 的 `gtnop.world.*` 维度目录，并核对 GalaxySpace、Amun-Ra、BartWorks 的星球/月球语言条目。

2.8.4 的半人马 Bb 译为“半人马Bb”；每日包已更新为“半人马座α星Bb”，本次采用后者。废土世界在每日包有独立维度词条，且与用户指定名称一致。

主世界沿用维度目录的“主世界”；Galacticraft 星图中的“地球”属于天体名称。Deep Dark 对应 `Underdark` 的“漆黑世界”，不能混用 Extra Utilities 另一维度的“深渊世界”。

## 全部 42 个维度

| 维度 | 修正前 | 对齐后 | 发行包维度键 |
| --- | --- | --- | --- |
| Everglades | 废土世界 | 废土世界 | `gtnop.world.dimensionDarkWorld` |
| Ross 128ba | 罗斯 128ba | 罗斯128ba | `gtnop.world.ross128ba` |
| Triton | 海卫一 | 海卫一 | `gtnop.world.triton` |
| Oberon | 天卫四 | 天卫四 | `gtnop.world.oberon` |
| Titan | 土卫六 | 土卫六 | `gtnop.world.titan` |
| Callisto | 木卫四 | 木卫四 | `gtnop.world.callisto` |
| Ganymede | 木卫三 | 木卫三 | `gtnop.world.ganymed` |
| Deimos | 火卫二 | 火卫二 | `gtnop.world.deimos` |
| Europa | 木卫二 | 木卫二 | `gtnop.world.europa` |
| Phobos | 火卫一 | 火卫一 | `gtnop.world.phobos` |
| Venus | 金星 | 金星 | `gtnop.world.venus` |
| Mercury | 水星 | 水星 | `gtnop.world.mercury` |
| Makemake | 鸟神星 | 鸟神星 | `gtnop.world.makemake` |
| Haumea | 妊神星 | 妊神星 | `gtnop.world.haumea` |
| Alpha Centauri Bb | 半人马座 α Bb | 半人马座α星Bb | `gtnop.world.centauribb` |
| Vega B | 织女星 B | 织女一B | `gtnop.world.vega1` |
| Barnarda E | 巴纳德 E | 巴纳德E | `gtnop.world.barnarda4` |
| Barnarda F | 巴纳德 F | 巴纳德F | `gtnop.world.barnarda5` |
| Tau Ceti E | 鲸鱼座 τ E | 鲸鱼座T星E | `gtnop.world.tcetie` |
| Miranda | 天卫五 | 天卫五 | `gtnop.world.miranda` |
| Kuiper Belt | 柯伊伯带 | 柯伊伯带 | `gtnop.world.kuiperbelt` |
| Neper | 奈珀 | 奈佩里 | `gtnop.world.neper` |
| Maahes | 马赫斯 | 马赫斯 | `gtnop.world.maahes` |
| Seth | 赛特 | 赛特 | `gtnop.world.seth` |
| Nether | 下界 | 下界 | `gtnop.world.Nether` |
| Overworld | 主世界 | 主世界 | `gtnop.world.Overworld` |
| End | 末地 | 末地 | `gtnop.world.TheEnd` |
| Moon | 月球 | 月球 | `gtnop.world.moon` |
| Mars | 火星 | 火星 | `gtnop.world.mars` |
| Asteroids | 小行星带 | 小行星带 | `gtnop.world.asteroids` |
| Ceres | 谷神星 | 谷神星 | `gtnop.world.ceres` |
| Io | 木卫一 | 木卫一 | `gtnop.world.iojupiter` |
| Enceladus | 土卫二 | 土卫二 | `gtnop.world.enceladus` |
| Proteus (海卫八) | 海卫八（Proteus） | 海卫八 | `gtnop.world.proteus` |
| Pluto | 冥王星 | 冥王星 | `gtnop.world.pluto` |
| Mehen Belt | 梅亨小行星带 | 迈罕带 | `gtnop.world.asteroidbeltmehen` |
| Ross 128b | 罗斯 128b | 罗斯128b | `gtnop.world.ross128b` |
| Barnarda C | 巴纳德 C | 巴纳德C | `gtnop.world.barnarda2` |
| Deep Dark | 深暗之域 | 漆黑世界 | `gtnop.world.Underdark` |
| Anubis | 阿努比斯 | 阿努比斯 | `gtnop.world.anubis` |
| Horus | 荷鲁斯 | 荷鲁斯 | `gtnop.world.horus` |
| Twilight Forest | 暮色森林 | 暮色森林 | `gtnop.world.TwilightForest` |

## 变更范围

仅更新 Viewer 的中文显示资源。维度注册键、ID、坐标和 Worker 协议不变。Proteus 的中文显示去掉额外的英文括注，与汉化包“海卫八”一致。罗斯与巴纳德的名称同时对齐空格格式。

下载的原始发行包和逐项比对 JSON 保存在本地 `build/translation-audit/`；不将整个汉化包打入 Viewer。译名来源归属 Kiwi233/Translation-of-GTNH 及其汉化贡献者。

### 发行文件校验

- `2.8.4.7z` SHA-256：`b07ea4d0bebc8fc9b4809f9f88310939e7af5384a495af46d104630cb14ce72a`
- `nightly-2026-09-15.7z` SHA-256：`799bf42edae4c46f21412ce51d15fe87e58d173b18fc9fe45a3e6fed19012728`
