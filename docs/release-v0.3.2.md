# Amidst-GTNH v0.3.2

本版同步 ChunkbaseNH 已确认的三个生物群系配色，并更新 GTNH 要塞图标。
发行版本 **0.3.2**，通信协议仍为 **25**。

## 本版更新

| 项目 | 新显示 |
| --- | --- |
| Shield | `#214E3A`，墨绿色 |
| Lush Swamp | `#39998B`，青绿色 |
| Bayou | `#68B02D` |
| GTNH 要塞（STRONGHOLD） | 末影之眼图标，地图标记与图层菜单同步 |

配色按群系名称匹配，不随注册 ID 改变，地图、群系列表与群系预览使用同一调色板。
用户提供的颜色配置仍优先于默认配色。磁盘缓存保存群系 ID，升级后会用新配色重绘，无需删除缓存。
本版仅更新显示资源和发行版本，生成算法沿用 v0.3.1。

## 安装

1. 解压 `Amidst-GTNH-v0.3.2.zip` 到独立目录。
2. 关闭 GTNH，移除旧 Worker，将 `amidst-gtnh-worker-v0.3.2.jar` 放入 `mods`，只保留一份。
3. 重启游戏，使用 Java 17 或更新版本运行 `run-viewer.bat`。Viewer JAR 不放入 `mods`。

目标环境为 GTNH 2.9.0-beta-3，其他版本尚未完成兼容性验证。请使用包内配套两端。

## 已知限制

- 要塞地图与“前往要塞”菜单仍使用不同预测线路；标记代表起始区块，不是传送门房间，详见[要塞定位](reference/stronghold-prediction.md)。
- 纯种子出生点仍为估算；结构、矿脉和小行星标记代表候选，无法保证实际存在。
- 地图缓存仍需连接 Worker；部分末地查询依赖已加载存档状态。

## 验证与交付

发布构建运行 Viewer、Worker 测试及 RWG / GregTech 参考算法对照，
检查两端版本、协议、共享数据类及 Worker SRG 映射。测试计数见包内 `build-info.json`。
本次未进行完整游戏实测。

- `Amidst-GTNH-v0.3.2.zip`：配套 JAR、启动脚本与文档。
- `Amidst-GTNH-v0.3.2-source.zip`：本次构建的源码。
- `Amidst-GTNH-v0.3.2-SHA256SUMS.txt`：发布文件校验值。

操作见[使用说明](usage.md)，开发见[维护说明](../CONTRIBUTING.md)，旧发行记录见[历史归档](archive/README.md)。
