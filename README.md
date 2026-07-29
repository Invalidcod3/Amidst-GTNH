# Amidst-GTNH

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

1. 将 `amidst-gtnh-worker-vNN.jar` 放入 GTNH 的 `mods` 目录。
2. 启动一次 GTNH，在 `config/amidstgtnhworker.cfg` 中把
   `enabled` 设置为 `true`，然后重新启动游戏。0.2版本开始
   `enabled` 已经默认设置为了 `true`
3. 保持 GTNH 运行，执行根目录的 `run-gtnh-amidst.bat`，或使用：

```powershell
java -jar build/release/amidst-gtnh-biomes-v0-2.jar -gtnh-worker
```

主程序与 worker 使用同一个版本，必须成对更新，版本不同则会报错。

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

注：BUILDING.md 和 gtnh-worker/README.md 目前都是 Codex 总结的，未来会
考虑完善构建和架构细节，方便进一步维护或开发。

## 运行要求

- 构建环境：Worker 模组需用 Java 21 构建，Amidst 可用 Java 21 或 Java 25 构建。
- Amidst 主程序：Java 17 或更高版本。
- worker 运行环境：目前2.9.x相关版本可用，2.8.x 或 2.7.x 暂未测试，大概率也是可用的。

worker 绑定本机回环地址(LocalHosts)，不会主动向外网开放服务。端口和共享口令可以在
`amidstgtnhworker.cfg` 或对应的 JVM 参数中配置。
>>>>>>> f9ddb08d (Release 0.2.0)
