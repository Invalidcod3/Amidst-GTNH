# v35：刷新黑块与群系预测性能

> 历史记录：安装要求、协议号及验证结论属于当时版本。当前状态见[发布说明](../release-v0.3.1.md)。

本版保留 v34 的通用 RWG/BOP 地形和地表回放、4×4 地图采样与精确光标查询。
没有恢复按群系名称修补结果，也没有通过跳过原生地表回调或降低采样精度提速。

## 黑块原因与修复

游戏区块加载/卸载通知会使对应 Fragment 重新加载群系。旧代码先将状态切到
`LOADING`，而 Drawer 只画 `LOADED`，所以一张已经生成的瓦片会在刷新期间消失。
刷新任务逐个移动，这就形成了黑色矩形在地图上扫过的现象。

v35 将“是否正在计算”与“是否已有可显示图像”分开：

- 同坐标、同维度的刷新继续显示上一次完成的图像及图标。
- ImageLoader 在新 BufferedImage 上完成绘制，再原子替换引用；不修改正在显示的图。
- 绘制失败保留旧图；第一次加载没有旧图时仍需要等待。
- 坐标复用、切换维度时清除旧图的显示资格，避免短暂显示错误地点或维度。

回归测试用可阻塞的绘制任务验证：计算中旧图内容不变，完成后才发布新图，失败不覆盖。
它验证这条代码路径；尚未在用户当前 Viewer 进程中热替换或做屏幕实测。

## 实际 GTNH 性能定位

在正在运行的 GTNH 上，经 `127.0.0.1:47117` 请求 8 个新区域：种子
`2777967236474336022`，X 从 32000 到 35584、Z=32000，每次 128×128 个
step=4 采样点，即一张 512×512 方块的地图瓦片。

v34 的 Worker 计算耗时为 359–612 ms/瓦片；这不是包含所有结构图层的完整 UI 耗时。
查询返回时间、排队时间和计算时间分别保存在 `v34-live-tile-timing.json`。

45 秒 JFR 记录中，有 386 个采样栈涉及 Worker，90 个栈顶落在
`RwgChunkBiomeReplay.replay` 的逐格初始化方块语句，约占这批样本的 23%。
还观察到反射权限检查、Float 装箱、原生 CellNoise/PerlinNoise 和地表回调。
采样占比只是热点证据，不能当成精确 CPU 时间分账。摘要为 `v34-worker-hotspots.txt`。

## 优化内容

### 首次计算

临时地形列按截断后的高度只有 257 种填充形态。回放器懒加载不可变列模板，
通过 `System.arraycopy` 复制到当前区块，代替每区块 65536 次分支与单个引用写入。
原生回调仍修改独立的临时区块数组，不会污染模板；元数据仍在每次回放前清零。

`RwgTerrainAccess` 将实际 manager 的 `getRiverStrength`、`getOceanValue`、
`calculateRiver` 和实际 realistic 对象的 `rNoise` 绑定为 primitive MethodHandle，
减少逐点反射参数数组、整数/浮点装箱及访问检查。浮点运算顺序和被调用的方法保持不变。

### 重复刷新

此前 live-world 请求完全跳过整瓦片缓存，只依赖最多 2048 个区块的混合缓存。
视野和结构查询超过工作集后，再次刷新旧瓦片可能重新执行大量地形回放。

`OverworldTileCache` 另外保存最多 256 个请求区域的**纯预测结果**，与小型混合工作集分开。
每次查询仍检查实际区块是否加载：加载时实时读取游戏最终群系，未加载时复用纯预测。
实际区块数据绝不会写进纯预测缓存，因此加载、卸载与已加载群系编辑都不会污染预测。
世界或 manager 对象变化时缓存清空。每次响应使用独立数组，返回值修改不会污染缓存。

典型 128×128 瓦片缓存上限约 16 MiB 的整数数据；最大允许请求大小下约 64 MiB，
另有数组/键对象开销。用户看过的瓦片超过上限后仍按 LRU 淘汰。

## 对比结果与限制

用冻结 v34 developer JAR 与当前实现交替测量，各 5 轮取中位数；两边使用相同
受控噪声/回调输入，4 张瓦片共 65536 个采样点，最终群系输出校验一致。

| 输入 | v34 | v35 | 吞吐提升 |
| --- | ---: | ---: | ---: |
| 单一条目区域 | 381.237 ms | 244.561 ms | 1.559× |
| 混合边界区域 | 542.333 ms | 297.005 ms | 1.826× |

这是首次计算的受控基准，不是实际整包提速承诺，也不包含新的整瓦片缓存收益。
v35 尚未装入正在运行的 GTNH 进程，因此不能用上述倍率直接推算其游戏耗时。
真实噪声和 painter 仍有必要的计算成本；本版不保证所有区域即时出图。

完整发布构建：Viewer 117 项通过、10 项开发工具测试跳过；Worker 35 项全部通过，
合计 152 项通过。实际 RWG JAR 对照的 5120 个混合/高度点、2048 个通用回调结果
以及历史地表规则对照仍通过。新增 6 项刷新与整瓦片缓存测试。

## 构建、测试与维护

```powershell
.\gradlew.bat assembleRelease '-PrwgReferenceJar=C:/path/to/RWG-alpha-1.5.2.jar' --offline
.\gtnh-worker\gradlew.bat -p gtnh-worker benchmarkReplay '-PbaselineWorkerJar=C:/path/to/worker-v34-dev-baseline.jar' '-Pgtnh.modules.codeStyle=false' --offline
```

基准需要冻结的 **developer JAR**，只能用于测试，不能放入游戏 mods。
本轮基线 SHA-256 为 `00b02c97177155a79656a128b210f237df08d093ef208031d001d9f6f8e5df74`。
发布包不包含此基线或完整 JFR 记录。

| 入口 | 用途 |
| --- | --- |
| `Fragment.hasDisplayData`、`Drawer.drawLayers` | 刷新期间保留已完成图像 |
| `ImageLoader.doLoadAtomic` | 完整图像的原子发布 |
| `RwgChunkBiomeReplay` | 原生回调与不可变地形列模板 |
| `RwgTerrainAccess` | 原方法的 primitive 调用绑定 |
| `OverworldTileCache` | 纯预测缓存与实时区块数据分离 |
| `RwgReplayBenchmark` | 冻结 v34 与当前代码的可重复比较 |

更新本包两个 JAR，替换 Worker 后重启 GTNH。保留相同缩放与图层，分别测试：
保持 Viewer 不动并移动游戏角色触发区块通知；拖向新区域；拖回已看过的区域；
切换维度；断开 Worker 后重新连接。用 `profile-viewer.bat` 保留日志。
群系误差仍使用 `Compare-WorkerBiomes.ps1` 检查已加载区块；完整精度范围见
[群系调用链说明](gtnh-biome-accuracy.md)。
