# Amidst-GTNH

当前正式版：**v0.3**。安装方法、主要更新与精度说明见 [v0.3 Release 说明](docs/release-v0.3.md)。

这是面向 GTNH 的 Amidst 分支。主程序通过一个独立的Worker
模组读取 GTNH 运行时注册信息，并显示主世界、下界、末地、
暮色森林和多个太空维度的生物群系与结构预测。

项目主要由Codex编写，有很多问题，欢迎发PR来完善代码。目前预测结
果应视为地图辅助信息；已经加载的区块会使用Minecraft 内存中的群系
数据，未生成区域则由当前模组和配置重放生成算法。

## 功能
- 主世界的生物群系和主要结构定位。
- 下界的生物群系和结构定位。
- 末地的HEE生物群系和结构定位，包括龙研的混沌岛屿。小行星矿脉。
- 星系的主要星球和地牢等结构定位。
- 稀有矿脉定位，钼和硫铟铜和暗物质。
- 批量导出坐标，或直接导入到旅行地图内。

## 使用方法

1. 将 `amidst-gtnh-worker-v0.3.jar` 放入 GTNH 的 `mods` 目录。
2. 启动 GTNH 到主菜单即可。Worker 首次生成的配置默认 `enabled=true`，
   当次启动即生效，无需为生成配置再重启一次。已有配置中主动设置的
   `enabled=false` 仍会保留；需要启用时请在启动前改为 `true`。
3. 直接双击渲染器 JAR 即进入 GTNH 模式，也可执行根目录
   的 `run-gtnh-amidst.bat`，或使用：

```powershell
java -jar build/release/amidst-gtnh-biomes-v0.3.jar
```

GTNH 模式下可以直接添加 `-gtnh-worker-port`、`-gtnh-worker-token` 等参数，
无需先写 `-gtnh-worker`；旧的 `-gtnh-worker` 参数仍然兼容。

可以先开 Viewer，再启动 GTNH。Worker 未监听时，Viewer 会持续等待，每隔
2 秒重试；游戏仍在加载、握手尚未完成时也会继续等待，连接成功后自动打开
地图窗口，无需重启 Viewer。配置明确为 `enabled=false` 时，需要在 GTNH
启动前将其改为 `true`；Viewer 不会代替游戏启用 Worker。

Windows 双击 JAR（由 `javaw.exe` 启动）时，会自动打开原生控制台，显示原有
标准输出、错误输出和异常堆栈。关闭地图窗口后，控制台保留输出，可手动关闭控制台窗口。
使用 `java -jar` 启动时继续使用当前控制台或输出重定向。GTNH 模式默认将应用日志追加到 JAR
旁的 `viewer.log`，该位置不可写时使用用户目录的 `amidst-viewer.log`；
也可通过 `-log <文件路径>` 指定。等待期间可用控制台的 `Ctrl+C` 结束 Viewer。

也可以双击 `run-gtnh-amidst.bat`（测试包内为 `run-viewer.bat`）；
脚本会保留控制台输出，退出后按任意键关闭。

原版 Amidst 的 Minecraft profile 模式只能通过命令行显式开启：

```powershell
java -jar build/release/amidst-gtnh-biomes-v0.3.jar -vanilla
```

`-profile`、`-mcjar`、`-mcjson` 需要与 `-vanilla` 一起使用。
请配套更新主程序与 Worker；两端协议版本不兼容时会明确报错。

v43 修复地图信息框和其他自绘文字的中文方块显示，改用支持系统字形回退的字体。只需替换 Viewer，与 v42 Worker（协议 21）兼容。见 [v43 字体修复说明](docs/gtnh-v43-fonts.md)。

v42 新增简体中文／英语切换，以及矿脉、流体的类型、高度、最低 L/Op、点位模式和数量上限等条件导出。见 [v42 使用说明](docs/gtnh-v42-language-export.md)。协议升级到 21，须配套更新两端。

v41 修复未加载维度被探矿目录遗漏的问题，恢复星系等探矿入口与维度方块图标，并排除没有资源和 POI 的维度。见 [v41 维度说明](docs/gtnh-v41-dimensions.md)。

v40 将矿脉、流体名称筛选改为按当前维度目录下拉选择，并增加逐区块最低 L/Op 筛选。详见 [v40 筛选说明](docs/gtnh-v40-filters.md)。

v39 新增 Structure / Ores / Fluid 标点模式、矿脉与流体探矿、运行时维度图标及 24 个额外探矿维度。探矿精度和背景限制见 [v39 探矿使用与精度说明](docs/gtnh-v39-prospecting.md)。

v38 加速罗斯 128b / Deep Dark 的最终群系采样，并补齐罗斯区块生成器的群系替换。见 [调用链、性能与维护说明](docs/gtnh-v38-dimensions.md)。
保留 v37 根据真实 JFR 采样优化的 RWG 网格复用、游戏 tick 时间预算和结构预测续算；准确底图完成后即可显示。
性能对照、维护入口及限制见 [v37 性能修复说明](docs/gtnh-v37-performance.md)，精度策略沿用 [v36 自适应精算](docs/gtnh-v36-adaptive.md)。

v35 修复瓦片刷新时的黑块，优化临时地形填充和原生调用，并缓存整张瓦片的纯预测结果。
性能证据与维护入口见 [v35 黑块与性能说明](docs/gtnh-v35-performance.md)。

v34 按区块调用实际 RWG/BOP 的地形与地表回调，统一取得最终群系；主世界光标停留后会异步查询
精确方块群系，并与地图的 4×4 采样值分开显示。新增已加载区块的预测对照工具。
实际 GTNH 调用链、端口实测和维护说明见 [主世界群系准确性](docs/gtnh-biome-accuracy.md)。

v32 修复单人存档按 ESC 暂停后 Worker 停止处理查询的问题。预测继续在内置
服务器线程执行，世界仍保持暂停。本次需要替换 Worker 并重启游戏；协议仍为 18，
可继续使用 v31 Viewer。入口、日志与测试步骤见 [Worker 排障说明](docs/worker-troubleshooting.md)。

v31 增加了最多 256 张近期瓦片的结果缓存和图层续算，并按已生成区域的边界
传播权重来选择下一张瓦片。保留 v29 的 RWG 算法优化和 v30 的并发限制。
缓存、边界权重、维护入口及调试方法见 [地图预测性能说明](docs/gtnh-performance.md)。

## 构建

仓库提供统一的 Gradle 构建入口。它会运行 Amidst 测试、打包可执行主程序、
独立构建并重混淆 Forge worker，然后把两个匹配的 JAR 放入
`build/release/`。

Windows：

```powershell
.\build.bat
```

Linux/macOS：

```sh
./build.sh
```

完整的环境要求、版本规则和单独构建 worker 的方法见
[BUILDING.md](BUILDING.md)。worker 的源码布局说明见
[gtnh-worker/README.md](gtnh-worker/README.md)。
崩溃、连接和发布归档的排查步骤见 [Worker 排障说明](docs/worker-troubleshooting.md)。

注：BUILDING.md 和 gtnh-worker/README.md 目前都是 Codex 总结的，未来会
考虑完善构建和架构细节，方便进一步维护或开发。

## 运行要求

- 构建环境：Worker 模组需用 Java 21 构建，Amidst 可用 Java 21 或 Java 25 构建。
- Amidst 主程序：Java 17 或更高版本。
- worker 运行环境：目前2.9.x相关版本可用，2.8.x 或 2.7.x 暂未测试，大概率也是可用的。

worker 绑定本机回环地址(LocalHosts)，不会主动向外网开放服务。端口和共享口令可以在
`amidstgtnhworker.cfg` 或对应的 JVM 参数中配置。
