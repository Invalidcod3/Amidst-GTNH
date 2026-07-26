# GTNH 主世界生物群系原型

这是 GT New Horizons 分支的第一步：让 Amidst 显示一个实际运行中的
GTNH 世界所使用的主世界生物群系分布。

## 为什么使用运行时 worker

GTNH 并不是简单地把原版的生物群系表换成 BOP 表。当前配置把世界类型
锁定为 `RWG`；RWG 启动时检测 BOP、Thaumcraft 等模组，把它们已经注册的
`BiomeGenBase` 加入自己的气候分类，最后由 `ChunkManagerRealistic` 选择
生物群系。实际结果同时取决于整合包版本、模组版本、Biome ID 和配置。

因此本原型没有在 Amidst 里复制一份容易失真的 RWG 算法。它增加了一个
很小的 Forge 1.7.10 模组，在完整 GTNH 实例完成模组初始化后：

1. 只监听 `127.0.0.1`；
2. 在 Minecraft 服务端 tick 线程上读取该世界真实的最终表面群系；
3. 通过一行一个 JSON 对象的 TCP 协议，把生物群系 ID、名称、颜色和采样
   结果交给 Amidst；
4. Amidst 使用运行时生物群系目录和颜色渲染地图。

这条路径会自然包含 RWG、BOP、Thaumcraft、核心模组和本地配置对主世界
生物群系选择的影响。普通世界继续调用 `WorldChunkManager#getBiomeGenAt`；
RWG 则通过一个范围很小、启动时严格校验的运行时适配器取得
`getBiomeDataAt`、`getRiverStrength`、同源噪声和 `riverBiome`。

RWG 的 `WorldChunkManager` 只返回河流替换前的基础群系；真正的河流 ID
原本要到 `ChunkGeneratorRealistic#replaceBlocksForBiome` 才写入区块 biome
array。worker 现在复用同一河流强度和噪声阈值，在不生成区块的情况下计算
最终河流群系。已经加载的区块则直接读取其权威 biome array。若目标 RWG
版本缺少所需成员，握手会明确失败，不会悄悄回退成一张没有河流的地图。

## 已核对的上游行为

实现时核对了以下源码快照：

- [GTNH 整合包仓库](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack)，
  commit `b1047ff7389c48c14b66abef8b3a122d33150542`。其中
  `config/defaultworldgenerator.cfg` 设置
  `Lock World Generator=true`、`World Generator=RWG`；
  `config/RWG.cfg` 的 `Climate Distance` 为 `1600.0`。
- [GTNH Realistic World Gen](https://github.com/GTNewHorizons/Realistic-World-Gen)，
  commit `37046ef9a2f807ae4feb0ad87e7ae65f756839a6`。其中
  `Support.init()` 检测 `BiomesOPlenty` 并调用 `SupportBOP.init()`，
  `ChunkManagerRealistic` 再从这些运行时列表中选择生物群系。

`config/biomesoplenty/biomeweights.cfg` 不是 RWG 主世界最终的权重表，不能
单独据此重建 GTNH 地图。

## 构建 worker

在仓库根目录运行：

```powershell
cd gtnh-worker
.\gradlew.bat assemble
```

可安装的重混淆 JAR 位于 `gtnh-worker/build/libs/`，文件名不带 `-dev`。
构建脚本来自 GTNH 的现代模组模板；构建端需要该模板支持的现代 JDK，
生成的模组代码仍以 Minecraft 1.7.10/Java 8 兼容语法编译。

## 在 GTNH 中启用

1. 把不带 `-dev` 的 worker JAR 放入 GTNH 实例的 `mods` 目录。
2. 启动一次实例，让 Forge 生成
   `config/amidstgtnhworker.cfg`。
3. 停止实例，把配置改为：

```properties
worker {
    B:enabled=true
    I:port=47117
    S:token=请换成一个本机共享口令
}
```

也可用 JVM 参数覆盖配置：

```text
-Dgtnh.amidst.worker.enabled=true
-Dgtnh.amidst.worker.port=47117
-Dgtnh.amidst.worker.token=共享口令
```

客户端停留在 GTNH 主菜单即可使用，不必先打开或创建存档。worker 会为请求
的种子建立不带存档和区块提供器的内存 RWG 采样上下文；它不会生成区块，也
不会写入 `saves`。若已经载入同一种子的 RWG 世界，worker 仍会优先读取已
加载区块保存的最终 biome array。专用服务端模式仍需服务端启动完成。
worker 不会从网络暴露端口；它固定绑定回环地址。

## 启动 Amidst

主程序需要 Java 17 或更高版本。构建主项目后可以直接启动，不再要求
Minecraft Launcher profile：

```powershell
java -jar target/amidst-gtnh-biomes-v0-1-v8.jar `
  -gtnh-worker `
  -gtnh-worker-port 47117 `
  -gtnh-worker-token "共享口令" `
  -gtnh-colors gtnh-biome-colors.json
```

Windows 下也可以使用快捷启动脚本：

```powershell
.\run-gtnh-amidst.bat -gtnh-worker-token "共享口令"
```

如果 GTNH 本身的 `JAVA_HOME` 指向 Java 8，可先把 `AMIDST_JAVA_HOME` 设置
为 Java 17/21 的安装目录；快捷脚本会优先使用它，而不会影响 GTNH。

`-gtnh-worker` 会自动建立名为 `GT New Horizons (worker)` 的内部 profile，
并直接连接 worker。可以用 `-mcpath <GTNH实例目录>` 让“打开存档”对话框
默认指向该实例的 `saves` 目录，但它不再是启动所必需的。

若不传 `-seed`，Amidst 主界面会自动打开一个随机种子。任何时刻都可以使用
`File -> New From Seed...` 输入任意数字或字符串种子，或使用
`File -> New From Random Seed` 再生成一个随机种子；地图会立即按新种子
完整刷新。worker 最多缓存最近 4 个种子的 RWG 采样上下文。

任意种子、出生点、结构及下界/末地生物群系查询使用 worker 协议 v8，
因此主程序和 worker JAR 必须一起更新。
GTNH 使用独立且默认开启的 `GTNH World Spawn` 图层：任意种子显示 RWG
预测出生点，读取存档时显示 `level.dat` 中的权威出生点，不再受旧版通用
出生点图层偏好值影响。

## 配色

默认配色不再直接照搬模组的 `BiomeGenBase.color`。GTNH 1.7.10 中这些值
经常用于别的游戏内用途，放在二维地图上会出现高饱和色、纯黑或相邻群系
难以区分。Amidst 会根据群系名称、温度、降雨量和地形高度生成一套克制的
地图色：

- `OCEAN` tag 完全不参与蓝色判定；名称包含 Ocean 也不会触发蓝色；
- 蓝色系默认只用于带 `RIVER` tag 的河流，以及未来明确提供独立
  `WATER` 分类且不同时带 `OCEAN` 的水体；
- `RIVER` 河流按实际温度使用冰蓝、冷蓝、温带蓝到暖青蓝的连续色阶；
- 非水体冰雪群系使用偏暖的雪灰色，不再使用浅蓝灰；
- 沙漠、沙丘、峡谷和恶地使用沙色到陶土色；
- 沼泽和湿地使用低饱和青绿色；
- 森林、雨林、草原和稀树草原使用不同明度的自然绿色；
- 山地、荒地、火山和蘑菇群系使用独立的灰、棕红和紫色系列。

同一类别会根据 Biome ID 做很小的明度偏移，使边界可辨但不形成彩虹噪声。

新版 worker 会把 GTNH 完成模组注册后的 Forge `BiomeDictionary` tags
一并发送给 Amidst。Forge 1.7.10 的 `OCEAN` 只是分类信息，一些模组陆地
也可能带有这个 tag，因此默认配色会彻底忽略它。带 `OCEAN` 的群系改按
`SNOWY`、`MESA`、`SANDY`、`SWAMP`、`JUNGLE`、`FOREST` 等其他 tag
分类；RWG 同时给这些群系附加的通用 `BEACH` tag 也不会抢在气候/植被
类别之前。只有 `OCEAN`、没有其他有效类别的群系会使用非蓝色的气候回退
色。用户通过名称/ID 覆盖文件或 Biome Profile 明确指定的颜色不受限制。

RWG 自带的六类河流会分别提供温度 `0.0 / 0.5 / 0.8 / 0.8 / 0.9 / 0.9`。
配色以这个实际温度为主，再由 `SNOWY`、`COLD`、`HOT` tag 校正。这样
Ice River 最浅，Cold River 偏冷蓝，Temperate River 居中，Hot River、
Wet River 和 River Oasis 逐渐偏暖青蓝。RWG 旧代码把 Temperate River
同时注册成了 `COLD`，这里会保留其温度信息，不会误画成冰河。

### GTNH 名称/ID 覆盖文件

复制仓库中的 `gtnh-biome-colors.example.json`，例如保存为
`gtnh-biome-colors.json`：

```json
{
  "byName": {
    "Alps": "#B8C6CE",
    "Rainforest": "#2F7447",
    "Wetland": "#557A68"
  },
  "byId": {
    "160": "#6F8F5C"
  }
}
```

颜色必须使用 `#RRGGBB`。名称匹配不区分大小写；如果名称和 ID 同时匹配，
`byId` 优先。文件可以只包含需要修改的群系，其余继续使用内置配色：

```powershell
.\run-gtnh-amidst.bat -gtnh-colors gtnh-biome-colors.json
```

### Amidst 标准 Biome Profile

原有 `biome/*.json` 配色文件也可以继续使用。在界面菜单里主动选择一个
Biome Profile 后，其中存在的 ID 会覆盖 GTNH 默认色；文件没有列出的 ID
仍回退到 GTNH 配色。因此既可以使用按名称覆盖的 `-gtnh-colors`，也可以
使用 Amidst 原有的按 ID 配色编辑流程。

也可以直接生成一张用于验证的 PNG：

```powershell
java -cp target/amidst-gtnh-biomes-v0-1-v8.jar amidst.gtnh.cli.GtnhBiomePreview `
  --token "共享口令" `
  --x -4096 --z -4096 `
  --width 256 --height 256 --step 32 `
  --colors gtnh-biome-colors.json `
  --output gtnh-overworld-biomes.png
```

下界预览在同一命令后增加 `--dimension nether`。图形界面可在
`Layers -> Dimension -> Nether` 切换（快捷键 `Ctrl/Cmd+Shift+2`）。
末地在图形界面中通过 `Layers -> Dimension -> End` 切换（快捷键
`Ctrl/Cmd+Shift+3`）；无界面预览使用 `--dimension end`。

这里每个像素间隔 32 方块，覆盖 8192×8192 方块。单次请求最多
65,536 个采样点。

## 当前正确性边界

- 生物群系开放维度 0（主世界）、-1（下界）和 1（末地）；结构预测同样
  支持这三个维度。
  下界使用单独的 Provider/WorldChunkManager 采样路径；当维度 -1 注册为
  BOP Provider 时，任意种子直接调用 BOP 的 `WorldChunkManagerBOPHell(long,
  WorldType)`，完整运行 `BiomeLayerHell` 源码链。它不调用或修改 RWG
  主世界采样、河流替换和 4x4 显示投票算法。已加载的下界区块优先作为权威
  数据；任意种子通过整合包实际注册的维度 -1 Provider 创建无存档上下文。
- 末地背景区分中央末地、虚空，以及极限末地生存（Hardcore Ender
  Expansion）的虫蚀森林、燃烧山脉和附魔岛三种岛屿群系。HEE 岛屿中心、
  群系抽选和地牢高塔会按模组源码随机流重放；任意种子预览按新末地处理，
  即历史末影龙击杀数为 0。岛屿的 208 方块着色范围是可读性轮廓，实际边缘
  仍由模组地形生成器雕刻。龙之研究混沌岛读取运行中整合包的开关和
  `Chaos Island Separation`，精确显示网格中心（原点岛按源码排除）。
- 原版主世界结构层仍被主动关闭，避免把错误的原版村庄、要塞等位置叠到 GTNH
  地图上。当前增加 RWG 实际调用的村庄、废弃矿井、要塞，以及经过 GTNH
  运行时验证的 Roguelike Dungeons 结构层。RWG 不调用原版神殿/女巫小屋
  生成器，所以不会显示这类误导性图标。
- 下界视图单独开放 1.7.10 原版下界要塞、匠魂下界史莱姆岛候选点和
  Automagy Nether Spire 候选点。要塞使用原版 16 区块区域算法，可精确
  定位。匠魂图标受运行中配置的维度白名单与稀有度控制，但岛体内部使用
  未绑定世界种子的随机字段，因此只能给出稳定的近似中心。Automagy 会
  精确重放配置概率与 X/Z 抽选；最终仍需实际区块中存在连续岩浆池和足够
  净空，所以标为 `Possible`。
- Roguelike 候选区块、40–99 方块入口偏移、RWG 最终群系过滤和地牢配置
  类型选择都由 worker 按实际种子与当前配置重放。地图按沙漠、森林、冰原、
  丛林、恶地、山地、平原、沼泽八类分别显示，菜单中可逐类开关。
- Roguelike 的最后一步会读取已生成入口周围的 9×9 地形方块；任意种子
  预览不生成区块，因此图标明确标为 `Possible`。陡坡、洞口、水体等实际
  地形可能让模组改试下一个位置或放弃生成。
- LootGames 游戏地牢会按当前 `general.cfg` 的维度菱形尺寸重放候选区块；
  最后仍有空间和方块检查，所以标为 `Possible`。匠魂史莱姆空岛会读取当前
  维度开关与 `Slime Island Rarity`，按 Forge 1.7.10 的区块随机数重放抽选。
  图标放在生成范围的稳定近似中心。两类结构都能在 `Layers` 中独立开关，
  并分别使用 LootGames 地牢墙砖和匠魂蓝色可食用史莱姆球的原始材质。
- 原版刷怪笼地牢使用 RWG 的 populate 随机流，并用相同洞穴生成器建立只读
  洞穴掩码，执行房间顶底与入口数量检查；标记标签会显示预测 Y 坐标。
  湖泊、其他结构及模组事件仍可能改变最终方块状态，因此标为 `Possible`。
  这项计算成本较高，使用独立查询和缓存，图层默认关闭。
- 神秘时代 4 灵气节点与远古祭坛（Eldritch Altar）会按当前
  `Thaumcraft.cfg`、群系和区块随机流重放。遇到树木生成导致随机流依赖实际
  地形的区块时，预测器宁可跳过，不输出已知不稳定的坐标。灵气节点使用节点
  研究图标，祭坛使用对应的黑曜石瓦材质；二者都可在 `Layers` 独立开关。
- Amidst 的四分之一分辨率请求会转换成显式的 4 方块步长；每个地图像素
  会在对应的 4×4 方块内取四个样本并投票。这样传输和绘制分辨率不变，
  但 RWG 河流和群系边界不再只由左上角一个方块决定。
  RWG 的
  `ChunkManagerRealistic` 不遵循原版 `GenLayer` 的四分之一坐标约定，
  不能直接把缩小后的坐标传给它。
- RWG 河流使用其运行时 `getRiverStrength()`、同源噪声阈值和当前
  `RealisticBiomeBase.riverBiome` 采样，不会加载、生成或保存地图覆盖区域
  的新区块。已加载区块直接采用区块中保存的最终群系。
- 生物群系使用内置 GTNH 地图配色；可通过名称/ID 覆盖文件或 Amidst
  Biome Profile 调整。水体严格按 worker 返回的 Forge tags 分类。
- 已验证主 Amidst 源码可编译、固定种子/坐标换算单元测试通过，Forge
  worker 可完成重混淆 JAR 打包。仓库中没有完整 GTNH 运行实例，因此仍
  需要在目标整合包上做同一种子的截图/区块边界端到端对照。
- 握手时会检查协议版本和生物群系注册表是否为空。由于真实 GTNH
  1.7.10 注册表可能包含别名或覆盖，同一个 Biome ID 的重复条目会由新版
  worker 规范化；客户端连接旧 worker 时也会合并并记录警告，而不会把它
  误判为握手失败。采样若出现握手时完全未注册的 ID 仍会立即停止并提示
  重新连接，以免悄悄使用错误颜色。
- v8 握手要求主程序和 worker 同步更新；旧 worker 会收到明确的协议不匹配
  提示。无论 worker 新旧，`OCEAN` 和 Ocean 名称都不会触发默认蓝色。

## 下一步接口

worker 协议已经加入通用结构记录（种类、子类型、坐标、确定性）。后续可以
继续按各模组真实的生成器实现其他地牢图层；在取得验证数据以前，仍不复用
Amidst 的原版结构算法。
