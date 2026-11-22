# Amidst-GTNH

这是面向 GTNH 的 Amidst 分支。主程序通过一个独立的Worker
模组读取 GTNH 运行时注册信息，并显示主世界、下界、末地、
暮色森林和多个太空维度的生物群系与结构预测。

项目主要由Codex编写，有很多问题，欢迎发PR来完善代码。目前预测结
果应视为地图辅助信息；已经加载的区块会使用Minecraft 内存中的群系
数据，未生成区域则由当前模组和配置重放生成算法。

**v0.4.2 正式版 / 协议 31** 支持 GTNH 2.9.0／2.9.1 的 **RWG2 Smooth（大陆模式）**，并保留 **RWG-Alpha**。
完整更新与兼容性范围见 [v0.4.2 发布说明](docs/release-v0.4.2.md)，使用方法见 [操作指南](docs/usage.md)。

## 功能

- 主世界的生物群系和主要结构定位。
- 下界的生物群系和结构定位。
- 末地的HEE生物群系和结构定位，包括龙研的混沌岛屿。小行星矿脉。
- 暮色魔法地图。
- 星系的主要星球和地牢等结构定位。
- 稀有矿脉定位，钼和硫铟铜和暗物质。
- 矿脉和流体图层。
- 根据花朵、潘马斯菜园／果树和树木筛选候选群系，支持 RWG-Alpha 与 RWG2。
- 批量导出坐标至csv，或直接导入到旅行地图内。


## 使用方法

1. 将 `amidst-gtnh-worker-v0.4.2.jar` 放入 GTNH 的 `mods` 目录；升级时先关闭游戏并移除旧 Worker。
2. 启动 GTNH 到主菜单即可。Worker 首次生成的配置默认 `enabled=true`，
   当次启动即生效，无需为生成配置再重启一次。已有配置中主动设置的
   `enabled=false` 仍会保留；需要启用时请在启动前改为 `true`。
3. 直接双击渲染器 JAR 即进入 GTNH 模式，也可使用：

```powershell
java -jar build/release/amidst-gtnh-biomes-v0.4.2.jar
```

GTNH 模式下可以直接添加 `-gtnh-worker-port`、`-gtnh-worker-token` 等参数，
无需先写 `-gtnh-worker`；旧的 `-gtnh-worker` 参数仍然兼容。

可以先开 Viewer，再启动 GTNH。Worker 未监听时，Viewer 会持续等待，每隔
2 秒重试；游戏仍在加载、握手尚未完成时也会继续等待，连接成功后自动打开
地图窗口，无需重启 Viewer。配置明确为 `enabled=false` 时，需要在 GTNH
启动前将其改为 `true`；Viewer 不会代替游戏启用 Worker。

原版 Amidst 的 Minecraft profile 模式只能通过命令行显式开启：

```powershell
java -jar build/release/amidst-gtnh-biomes-v0.4.2.jar -vanilla
```

`-profile`、`-mcjar`、`-mcjson` 需要与 `-vanilla` 一起使用。
请使用本版配套的 Viewer 和 Worker；替换 Worker 后重启游戏，`mods` 中只保留一份。
本版发行号、包内版本和文件名统一为 **0.4.2**，通信协议仍为 **31**。
发行 ZIP 内含配套 JAR 和 `run-viewer.bat`，解压后可直接使用启动脚本。

### 选择生成版本

| 已连接实例的 RWG2 | Smooth 选项 | 算法 |
| --- | --- | --- |
| 2.0.6（GTNH 2.9.0） | RWG2 Smooth (GTNH 2.9.0) | 2.9.0 的生成规则 |
| 2.0.9（GTNH 2.9.1） | RWG2 Smooth (GTNH 2.9.1) | 2.9.1 的生成规则 |

每次连接只列出当前实例支持的 Smooth 版本，另保留 RWG-Alpha。
识别依据是实际加载的 RWG2 构建，不是启动器实例名；手动升级模组时也会随之适配。
旧的 Smooth 默认设置会跟随当前实例；旧 Worker 未提供版本信息时显示通用名称，建议同时更新配套 Worker。
更换游戏实例后重新连接 Viewer。具体算法差异和网页端／Wiki 后续同步要点见[版本同步说明](docs/reference/rwg2-version-sync.md)。

### 按植被高亮群系

打开地图右下角的生物群系筛选控件，点击“植被条件…”，搜索或多选植物，
再点击“应用高亮”。匹配群系会自动勾选，之后仍可在完整列表中自由增减选择。
支持匹配任一植物或同时匹配全部植物，RWG-Alpha 与 RWG2 分别使用各自的群系规则。

规则依据 GTNH 2.9.0／2.9.1 默认配置，结果表示可能生成这些植物的群系，不是植物的精确坐标；
自行修改的植被配置不会自动读取。详细分布见[植被分析](docs/reference/vegetation.md)和[逐项规则表](docs/reference/vegetation-catalog.md)。

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
崩溃、连接和发布归档的排查步骤见 [Worker 排障说明](docs/archive/worker-troubleshooting.md)。

注：BUILDING.md 和 gtnh-worker/README.md 目前都是 Codex 总结的，未来会
考虑完善构建和架构细节，方便进一步维护或开发。

## 运行要求

- 构建环境：Worker 模组需用 Java 25 构建，Amidst 可用 Java 21 或 Java 25 构建。
- Amidst 主程序：Java 17 或更高版本。
- Worker 支持 GTNH 2.9.0 的 RWG2 2.0.6、GTNH 2.9.1 的 RWG2 2.0.9 大陆模式，以及 RWG-Alpha；其他 RWG2 构建需单独核对，2.8.x / 2.7.x 尚未做整包验证。

Worker 绑定本机回环地址（localhost），不会主动向外网开放服务。端口和共享口令可以在
`amidstgtnhworker.cfg` 或对应的 JVM 参数中配置。
