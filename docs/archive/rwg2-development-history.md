# RWG2 开发与性能调查历史

> 本文保留 2026-11-01 及此前的开发记录。0.4.1–0.4.12 是内部试用编号，已合并为 **v0.4.0 正式版**；文中的“当前”、待办和路径仅代表记录当时状态，不作为安装指南。当前行为见 [正式版发布说明](../release-v0.4.0.md) 与 [RWG2 实现边界](../reference/rwg2-adaptation-plan.md)。

目标是在 Amidst-GTNH 中增加生成器设置，优先支持 GTNH 2.9.0 的 RWG2 大陆模式，并保留旧 RWG。实现按实际模组、世界类型和生成状态选择适配器；后续 2.9.x 版本通过相同的能力识别机制接入，逐版本验证。

截至 2026-11-01，已实现本地 GTNH 2.9.0 的第一阶段适配，当前版本为 **v0.4.12 / 协议 31**。以下早期方案与后续清单保留供继续开发，当前完成范围以本节为准。基线为 `56e51d5982f98da576a9792c04c0c273aac78066`。首轮 v0.4.0 游戏实测暴露了群系目录的 8 位限制，修复细节见下节。v0.4.12 尚待完整游戏验收；不修改整合包群系配置或存档。

## 按图层预测与结构并行（内部试用 v0.4.12）

- 确认底图任务完成时会释放上下文及请求名额；旧空闲线程等待 30 秒／永久保留并不持续消耗算力。真正的限制是结构长期单通道，以及 Viewer 开关此前只隐藏图标，`standard` 请求仍计算全部 RWG2 洞室。
- Viewer 为每次地图加载捕获可见结构类型，通过可选 `structureTypes` 字段发送。图层选择只存在于本次线程作用域；退出或取消后恢复，不污染导出／独立查询。结构缓存将类型集合纳入键，切换开关与局部区块失效不会复用缺项结果。
- Worker 在算法入口筛选村庄／矿井／要塞、Roguelike、模组结构、RWG2 洞室和神秘时代查询。普通 LootGames 与两类洞室小游戏分别选择；RWG2 分区缓存按熔岩、河道、海底神殿记录已算部分，后续开启仅补算缺项。保留原生随机选择：隐藏暮色门不会把同一位置改判为小游戏。神秘时代同一随机序列中的必要前序计算保留。
- 底图加载期间一个结构请求；底图队列与活动计算清空后立即放宽到最多 `min(4, Viewer 线程设置)` 个。再次拖动时立即恢复底图调度，已运行结构安全完成／取消，新结构恢复一个的限制。底图与结构使用独立的有界请求名额，避免单线程设置时相互阻塞。
- Worker POI 池最多 `min(4, 请求并发, max(1, 逻辑处理器数 − 2))` 个计算任务、另一个准备好的后继；初始化仍在游戏线程，运行中的上下文独占且不能在取消尚未退出时复用。Viewer 底图／结构线程和 Worker 底图／结构线程空闲五秒后退出；缓存保留有界复用，不进行强制 GC。
- 关闭暮色门和河道小游戏后不再做河道 POI 定位；关闭下界门和熔岩小游戏后不再做熔岩 POI 定位。底图本身所需的河道地形规则仍完整执行。所有结构关闭后没有瓦片结构查询。
- 协议仍为 31，空筛选表示不请求，缺省表示旧版全量查询。旧 Worker 可连接但忽略新筛选；要获得本次收益必须安装 Worker 0.4.12 并重启游戏。继续覆盖两个 v0.4.0 发布文件，整合包 mods 由用户维护。

发布检查：Viewer 236 项（226 通过、10 项开发工具测试跳过），Worker 121 项全部通过。覆盖关闭洞室组时零次定位采样、选择变化补查、串行与四路原生地下数组一致、单线程设置下请求名额独立，以及底图完成后即时扩展和再次拖动。`assembleRelease`、安装映射与协议契约检查通过，报告 `build/pause-audit/v412-release-verification.json`；尚未进行完整游戏窗口验收。

## 共用磁盘缓存停顿（v0.4.11，历史记录）

- 用户反馈 RWG2 和 RWG-Alpha 都会出现“地图仍可拖动，但新瓦片与标记一起停止几秒”。当前日志没有对应的重连／繁忙重试证据；抓取时线程已空闲，未捕获用户停顿现场，不能据此排除所有其他来源。
- 确认一条能复现同类停顿的 Viewer 路径：收到群系响应后同步写缓存，每 32 次写入（含第一次）遍历并排序整个目录；排序比较器反复查询文件时间，清理全过程持有所有缓存读写共用的锁。它与地形算法无关，也会拖住等待底图的后续结构加载。
- 本机 4,110 个缓存文件、269,418,720 字节，只读重放旧扫描／排序产生约 92,872 次文件属性查询。按普通应用权限连续三轮耗时 2.717 / 2.756 / 2.833 秒；每文件只读一次属性后为 0.182 / 0.163 / 0.146 秒。诊断隔离环境曾放大到 7.7–8.9 秒，不作为正常应用基准。报告 `build/pause-audit/cache-scan-unrestricted.txt`；测试不删除或改写用户缓存，计时不包含实际淘汰。
- 写入与清理移至共用单后台线程，队列最多等待 64 项，空闲 15 秒退出。队列满时丢弃可选磁盘保存，绝不回退到调用线程执行；瓦片无需等磁盘写完。待写数据使用独立副本并可直接读取。取消整段读写互锁，保留临时文件整体替换、CRC、256 MiB 目标容量及 Worker 最新缓存标识校验。
- 清理一次读取每个文件的时间与大小，排序不再触盘；容量未超限直接结束，不触碰临时文件、非缓存文件、目录或符号链接。关闭缓存时跳过尚未开始的写入。旧文件格式兼容，不必清空目录。
- 回归覆盖慢磁盘不阻塞调用方与缓存读取、待写数组隔离、队列拒绝、关闭后的排队写入和按时间淘汰。Viewer 232 项（222 通过、10 项开发工具测试跳过）；完整构建与配套 Worker 检查见发布报告。
- 协议仍为 31。本次只有 Viewer 功能变化；Worker 0.4.11 仅同步版本元数据，已安装 Worker 0.4.10 可继续使用。仍覆盖两个 v0.4.0 发布文件，整合包 mods 由用户维护。

## 洞穴异步查询与出生点补齐（v0.4.10，历史记录）

- Viewer 先发布精细底图，结构完成后发布新快照；不混入已失效的旧图标。单独一个 POI 请求线程不占用底图并发，即使底图并发为 1，慢结构查询也不会挡住其他瓦片。保留取消、维度与刷新代际检查。
- Worker 的 `Rwg2PoiPool` 使用一个空闲 15 秒后退出的后台线程，最多一个活动任务和一个后继；初始化、Forge 注册与实际世界证据留在游戏线程。洞室候选及刷怪笼地牢的洞穴重放使用独占上下文；未知洞穴替换器保留游戏线程路径。运行中的任务退出后才可复用其上下文。
- 河流洞室保留原生 2 格扫描、0.35 初筛、4/2/1 步进爬峰、0.82 阈值、山脉邻域、熔岩排除及区块归属。`Rwg2JunctionStrength` 缓存轴向噪声与 Voronoi 特征点，先排序距离平方，只对第一／第三近点开方（25 次降到 2 次）。游戏生成规则与采样密度不变。
- 两种噪声各 30,000 点（含负坐标、零、边界和 ±3000 万范围）交汇强度与原生逐位一致；两个 512×512 代表区域各 1024 区块的洞室中心逐项一致。每种模式一轮预热、五轮交替计时，报告 `build/rwg2-followup-audit/v410-junctions.json`。这是算法微基准，不代表整包 FPS。
- RWG2 随机种子出生点此前直接返回 `UNAVAILABLE`，现接入本模式完整表面与原生游走。存档记录优先；独立结果标为估算，不声称覆盖第三方事件／结构装饰。出生点变化只刷新附近瓦片。旧 RWG 保留，边界见 [出生点维护](../reference/spawn-prediction.md)。
- 协议 31，两端内部版本 0.4.10；继续覆盖两个 v0.4.0 文件。整合包 mods 不自动改动，Worker 由用户安装并重启游戏。

最终发布检查：Viewer 229 项（219 通过、10 项开发工具测试跳过），Worker 118 项全部通过。独立 POI 线程重放的地下方块与串行结果一致，单底图线程遇到阻塞 POI 仍可完成其余瓦片的调度测试通过。

| 噪声 | 原生洞室定位 ms | 优化后 ms | 耗时减少 |
| --- | ---: | ---: | ---: |
| Perlin | 9.59 | 4.55 | 52.5% |
| OpenSimplex | 11.80 | 4.46 | 62.2% |

## 特殊地形计算与首屏启动（v0.4.9，历史记录）

- 确认海岸会退出纯海洋快捷路径，恢复完整地形混合、表层和标记计算；山地河流交汇还要进行隧道／洞室 carving。选定区域中完整地形与噪声计算占主要耗时，不能通过降低边界精度绕过。
- 原生 `nearbyMountainChainInfluence` 对每个区块的 256 根柱重复检查 21×21 邻域，最多 112,896 次。Worker 的已校验隔离副本现在每区块建立一次山地掩码，使用固定几何距离排序表寻找最近有效样本；保留原生浮点表达式和半径边界，零山地掩码直接返回原生结果 0。游戏里的 RWG2 类、地形规则及采样间距不变。独立采样器各自持有掩码，常量查找表只读。
- 首屏此前在界面线程一次创建 1024 个瓦片，单是群系和图像数组就约 132 MiB；现在按 64 个的小批次增长，对应每批约 8.25 MiB，不限制地图总大小。
- 首次状态查询从 revision=0 获取保留的区块变化，取消 Viewer 人为附加的首次全屏失效。Worker 报告切换世界或变化历史溢出时仍完整刷新，避免遗漏变化。
- 采样器最多准备实际计算线程数加两个后继任务（合计不超过 64）。过量请求暂存唤醒回调，不再为每个等待连接创建大对象；完成或取消后复用空闲上下文，后继任务可跨游戏 tick 连续执行。取消中的正在执行任务不会提前出借上下文。单线程容量等待、取消唤醒与 8 路并行均有回归覆盖。
- 本次 Worker 和 Viewer 都有修改，需要更新两端并重启游戏，协议保持 31；交付仍使用两个 v0.4.0 文件名，内部版本 0.4.9。整合包 Worker 由用户自行安装。

分区测试使用本机 RWG2 2.0.6 JAR、种子 9182741，覆盖 Perlin／OpenSimplex。每个代表坐标附近的 128×128 区域有 64 个区块，两轮预热后交替测试五轮，表格为墙钟时间中位数。测试 JVM 使用原生群系算法，并给原生／隔离双方相同的原生山地包装群系测试池；未启动完整 BOP／EtFuturum 整合包，因此是算法微基准，不是实际游戏 FPS 或启动等待时间。海洋对照路径未改动，其计时涨跌反映测量波动；海岸与交汇区域仍然比纯海洋重。

| 噪声 | 区域 | 优化前 ms | 优化后 ms | 耗时减少 |
| --- | --- | ---: | ---: | ---: |
| Perlin | 海洋对照 | 9.96 | 10.16 | -2.0% |
| Perlin | 海岸 | 38.98 | 37.16 | 4.7% |
| Perlin | 内陆 | 28.49 | 23.57 | 17.3% |
| Perlin | 河流 | 31.32 | 25.30 | 19.2% |
| Perlin | 山地河流交汇 | 51.44 | 38.79 | 24.6% |
| OpenSimplex | 海洋对照 | 12.03 | 11.92 | 0.9% |
| OpenSimplex | 海岸 | 45.26 | 37.40 | 17.4% |
| OpenSimplex | 内陆 | 62.26 | 48.14 | 22.7% |
| OpenSimplex | 河流 | 32.14 | 27.93 | 13.1% |
| OpenSimplex | 山地河流交汇 | 51.16 | 48.05 | 6.1% |

10 个区域逐列比对 163,840 个群系值和同等数量的高度值，全部一致；另外覆盖山地掩码随机变化的浮点位级对照，以及原生完整区块、地下洞室和并行采样对照。报告位于 `build/rwg2-followup-audit/v49-boundaries.json`。可选 JVM 参数 `-Dgtnh.amidst.worker.profileTerrain=true` 开启 sampler 的分阶段统计，默认关闭。

发布构建检查：Viewer 227 项（217 通过、10 项开发工具测试跳过），Worker 113 项全部通过，含本机 RWG、RWG2、GregTech 参考对照。实际整合包窗口的首屏与持续缩放仍待更新后验收。

## 局部刷新与连续加载（v0.4.8，历史记录）

- 修复局部游戏区块通知使所有结构图层失效的问题：全局失效会取消全部活动瓦片、等待排空后重排，造成整批停顿。现在只重算区块所在瓦片及一圈邻域，保守覆盖跨瓦片结构读取；缓存清理和加载刷新共用同一范围。其他区域及维度的活动任务、缓存均保留。
- 同一瓦片的通知合并为一次待刷新标记。受影响的活动任务完成后再更新，避免同时写入同一缓冲；收到新通知的旧结果不能发布，也不能重新放入结构缓存。仍保留 4 格精细采样、最多 64 并发和底图／结构同步发布。
- 更新已经可见的瓦片不重新设置淡入透明度，避免刷新期间旧地图变暗。新坐标或新维度仍正常淡入。
- 本机 Viewer 日志还记录了 `World changed during background biome query`。仅对 Worker 明确定义的地图采样快照过期错误短等待重试（默认 100 ms），不触发离线提示或减半并发；通信拥堵保护和真正的算法错误仍保留。
- 这是 Viewer 修复，协议仍为 31，Worker 功能与 0.4.7 相同，已安装者不必重装。发布仍覆盖两个 v0.4.0 别名；整合包 Worker 由用户自己安装。

自动化回归包括局部更新期间保持其他瓦片加载、重复通知合并、过期完成结果不发布、旧 RWG 结构更新、跨维度缓存隔离、受影响／未受影响的并行结构查询以及快速重试。24 瓦片测试中，局部通知只使 2 个在范围内的瓦片补算底图（26 次底图加载、24 次结构加载）。这不是整合包性能测速；完整游戏窗口的持续缩放流畅度仍需实测。

发布验证：Viewer 225 项（215 通过、10 项开发工具测试跳过），Worker 112 项全部通过，含本机模组参考对照。格式、发行协议一致性及 JAR 完整性检查通过，两个原有文件已整体替换并校验 SHA-256，未修改整合包 mods。记录见 `build/rwg2-followup-audit/v48-release-verification.json`。

## 洞室分类、配色与断连恢复（v0.4.7，历史记录）

- Worker 已有三种 LootGames 来源：普通菱形选点、`RWG2_LAVA_CAVE`（Y=39）、`RWG2_RIVER_CHAMBER`（Y=66）。本次将它们拆为互斥的 Viewer 图层及坐标导出类型，橙框表示熔岩洞室，青框表示河道洞室；保留高度与候选／已发现状态，复用同一结构查询缓存。
- 本地 RWG2 2.0.6 的 `LandmarkDecorations.decorateLavaCenter` 明确在 `getVolcanoVicinityCoordinates(centerX, centerZ) != Long.MIN_VALUE` 时退出。小游戏所在的熔岩洞室不是火山下的岩浆室，不能据此定位火山。现有 Worker 的对应排除逻辑已核对，游戏和下界门分支都有回归覆盖。
- Phantasmagoric Inferno 使用 `#9D2038`，Tainted Land 使用 `#30203F`，颜色不随群系 ID 改变。
- 2026-11-01 本机游戏日志记录 Worker 在写回时发生连接中止／重置；同段日志没有给出游戏崩溃证据。Viewer 代码确认超时和无响应会终止瓦片加载、遗漏重试。现在临时故障自动重试，读超时发送 CAN 撤销旧任务；请求拥堵时每五秒最多减半一次并发，恢复十秒后逐步加回，最高仍为用户设置（至多 64）。协议、认证及真实算法错误不吞掉。路径点导入通信失败不自动重发。
- Worker 连接处理仍为 72 个线程，待处理连接队列限制为 72，超额返回可重试繁忙信息；通过短读取检测 EOF／CAN，不依赖无法判断 EOF 的 `ready()`，断连无需等待新游戏 tick 即可移除任务。关闭 Worker 会关闭活动和排队连接。取消时的写回竞争降为调试日志。
- 切换图层也会取消正在等待的旧加载任务，避免开启同步发布后因重试阻塞图层状态更新。协议保持 31，建议 Viewer 和 Worker 同时更新；整合包 Worker 仍由用户自行安装。

发布验证：Viewer 216 项（206 通过、10 项开发工具测试跳过），Worker 112 项全部通过，含本机 RWG、RWG2 和 GregTech 参考对照。三类 LootGames 图标已渲染检查。两个 v0.4.0 别名以整文件重命名替换并核对 SHA-256，避免修改运行中的 JAR 内容。记录位于 `build/rwg2-followup-audit/v47-release-verification.json`；尚未在更新后的完整整合包运行窗口进行大视野验收。

## 底图与结构同步显示（v0.4.6，历史记录）

- RWG2 以单个 512×512 格瓦片为单位，将精细底图和已开启的结构一起发布。刷新期间显示上一版完整瓦片；初次加载则等所需图层全部完成再显示，不设置整屏等待屏障。矿脉／流体仍独立加载。旧 RWG 保留原有逐层显示。
- 显示数据与加载缓冲分离，每次绘制固定读取同一份瓦片快照，避免底图画完后恰好换代、结构却取到下一版。地图下的群系提示也读取已发布的群系数据。
- 已完成底图的瓦片优先补齐结构。最多四个结构任务与其他瓦片的精细底图计算交错执行，不再等并行底图全部结束才开始结构查询；底图并发上限仍为 64，刷新旧瓦片同样保留完整并发。
- 主动刷新会取消被取代的任务，旧任务即使已完成但尚未被接收也不能发布。图层声明与版本只在活动任务排空后更新；关闭的结构不参与下一次发布等待。
- 开启耗时结构会推迟新瓦片第一次出现，这是保持图层同时出现的代价。自动化回归覆盖原子发布、绘制期间换代、关闭图层、取消与缓存复用、过期结果以及底图与结构并发调度；尚未在完整运行中的整合包窗口验收。
- 本次是 Viewer 调整，协议保持 31，已安装 v0.4.5 Worker 时无需重新安装。交付继续覆盖两个 v0.4.0 文件名，内部版本为 0.4.6。

发布验证：Viewer 211 项（201 通过、10 个开发工具项跳过），Worker 110 项全部通过，含本机 RWG、RWG2 与 GregTech 参考 JAR 对照。协议契约、安装映射、格式和发布构建检查通过。交付校验记录：`build/rwg2-followup-audit/synchronized-release-verification.json`。

## 精细底图后台并行（v0.4.5，历史记录）

- 将 RWG2 原生最终群系计算移入独立计算线程池。主线程只准备／租用采样器、捕获世界和噪声模式、接收结果及读取真实已加载区块；后台任务不接收真实存档对象。等待后台结果时，请求完全离开游戏队列，完成后再唤醒，不通过反复轮询耗尽 tick 预算。
- 每个任务独占 manager、噪声实例、随机数、方块／群系数组及可变缓存，manager 的气候放置列表复制。背景计算完成前不会归还上下文；取消排队任务会立即移除，已执行的任务在当前原生区块结束后停止。纯种子预测与实际区块覆盖分别保存，世界／manager／噪声模式变化时拒绝旧结果。
- 审核原生及移植群系的采样调用：160 多个 rNoise、rReplace、rMapGen、terrain 和 surface 回调无实例／静态字段写入，World 调用只允许读取独立预览世界的种子。运行时核验 237 个 2.0.6 类资源与已注册回调类型；未知实现回退原游戏线程路径。没有调用世界装饰、真实区块加载或 Forge 地表事件；配置变更仍需重启。
- RWG2 底图请求跟随 Amidst 的线程数设置，上限从 4 提升到 64，并将目标线程数发给 Worker。Worker 计算上限默认 `max(1, 逻辑处理器数 − 2)`，最多 64；可用游戏 JVM 参数 `-Dgtnh.amidst.worker.rwg2Threads=N` 覆盖。后台实际线程数取两端较小值。计算按瓦片并行，一张瓦片仍是一个任务。
- 高并发只调度 RWG2 主世界底图；旧 RWG、结构阶段及其他维度仍最多 4，矿脉／流体仍最多 2。Worker 网络处理容量为 72；实际世界读取、结构和资源计算仍沿用游戏线程预算。
- 版本为 0.4.5、协议 31，两端配套更新；本地交付继续覆盖两个 v0.4.0 文件名，整合包 Worker 由用户自行安装。

实际安装 JAR 的三组基准，每组生成 4 张 512×512 格精细瓦片：串行分别 1759.038、1826.703、1408.531 ms，4 线程分别 649.788、748.128、527.152 ms，约 2.4～2.7 倍。交替执行顺序，两组各使用冷独立上下文；每组八个上下文的准备耗时约 21～30 ms，不计入上述生成耗时。两种噪声、多种种子共 196,608 个精细像素完全一致；此前原生方块和群系对照继续通过。基准不包含真实游戏排队、完整模组事件与绘制，不是整屏／FPS 保证。记录：`gtnh-worker/build/reports/performance/rwg2-parallel.json`。

新增验证还覆盖 8 个采样回调真正同时执行、构造仍在主线程、背景停放时状态查询畅通、取消停放 TCP 请求无需新游戏 tick、取消不会提前复用正在计算的数组、已加载群系覆盖不污染种子缓存，以及 Viewer 8／32／64 线程传递和结构阶段限流。发布检查：Viewer 204 项（194 通过、10 个开发工具项跳过），Worker 110 项全部通过；格式、配对协议、安装映射与构建检查通过。完整整合包运行验收仍需用户配套更新并重启后进行。

## 直接精细预览与并行调度（v0.4.4，历史记录）

本节的“原生世界回调仍串行”描述的是 v0.4.4；当前 RWG2 底图后台并行以上节为准。

- 按使用反馈删除粗预览及对应采样入口。每个 512 格瓦片从一开始就显示 128×128 个 4 格原生样本，随后才加载结构；已完成精细图片在刷新期间保留。群系混合与边界继续由原生算法判定。
- 地图请求默认并发上限由 2 提到 4，仍受界面线程数设置限制；Worker 网络处理线程从 4 增到 8，可同时容纳地图、两个资源查询及短状态请求。提高的是请求、缓存及绘图流水线的并发，原生世界回调仍在游戏线程内串行分段执行，未提高每 tick 的计算预算。
- Viewer 等待响应时每至多 200 ms 检查任务是否取消；移出视野或取消资源请求时发送 CAN 字节，Worker 每 50 ms 检查并撤销未完成队列任务，当前原生回调到安全边界后退出。分段到达的 JSON/UTF-8 字节会保留，整体超时规则不变；完整响应已收到时仍可缓存。
- 精确地形构造按连续石头／水／空气区间填充，并移除重复清空方块数组。混合输入缓存保持 32,768 项容量，改用四路组相联的基本类型坐标键，减少装箱和链表维护。没有降低采样率或改变原生噪声、地表替换与洞口标记。
- 配对协议升至 30；发布目录仍覆盖两个 v0.4.0 文件名，内部版本为 0.4.4。由用户自行安装整合包中的 Worker。

对照基准以此前的精确路径（已有相邻群系缓存和海洋快速路径）为基线；两轮预热后交替顺序运行六轮，每轮为一个 512×512 格精细瓦片的 1,024 个相邻区块，额外核对共 2,097,152 列完全一致。计算中位耗时由 430.759 ms 降到 383.046 ms（减少约 11.1%），采样线程 CPU 中位耗时由 429.688 ms 降到 375.000 ms（减少约 12.7%）；六轮均更快。此前 64 区块小样本出现明显波动，因此不采用早期单轮结果作为最终提速结论。另保留原生入口的 100 区块、25,600 列群系及 6,553,600 个地下方块位置对照。基准结果见 `gtnh-worker/build/reports/performance/rwg2-exact.json`，仅代表计算部分，不含游戏排队、通信、绘制和完整整合包事件。完整游戏存档验收仍需配套更新后进行。

回归测试覆盖首帧即为精细图、先底图后结构、缓存复用、六个长请求并行时短状态查询仍可完成、无需新游戏 tick 即可取消排队任务、资源线程中断取消、分段 UTF-8 响应，以及缓存冲突和极端地形高度。发布测试：Viewer 201 项（191 通过、10 个开发工具项跳过），Worker 107 项全部通过，包含本机 RWG、RWG2 和 GregTech 参考 JAR 对照。配对协议、安装映射、格式及发布构建检查通过。

## 分阶段加载、资源图层与剩余结构（v0.4.3，历史记录）

下述粗预览曾在 v0.4.3 启用，现已在 v0.4.4 删除；其他资源与结构修复继续保留。

- 可见区域先以 64 格步长取 8×8 个原生样本，Viewer 扩展成临时预览；随后补齐 128×128 个 4 格样本，最后执行结构图层。每完成一个阶段即释放加载任务，优先填补其他可见空白瓦片。已有精细图片在刷新时保留，粗预览不会写成精细缓存，也不会作为结构、群系筛选或验证输入。
- 粗样本取每个 64 格区域内中央的一个精细像素中心（+34 格），与精细图的 +2 格采样约定一致。边界混合、河道和洞口群系仍由原生算法判定，Viewer 只扩展显示粗预览。
- 本机运行中的 0.4.2 Worker 只读抽样：种子 `-8032190410118151232`，瓦片左上 `(18432,-18432)`，粗采样 **145.72 ms**、随后精采样 **1594.78 ms**、精细缓存命中 **64.68 ms**；64 个粗样本均与对应精细样本一致。该记录包含通信与排队，只代表一次单瓦片测量，不是整屏或完整生成提速倍数。记录位于 `build/rwg2-followup-audit/progressive-live.json`。
- 资源图层空白的已复现原因是 256 格瓦片在当前视野下超过 256 瓦片上限，提示又被 CPU/FPS 信息遮挡。现在使用 512 格瓦片、512 项上限、最多两个请求、视野中央优先，提示上移；切换图层、刷新和移出视野的旧请求不能回写新结果。Worker 的矿脉与流体算法原本已可用于 RWG2，修复的是 Viewer 加载入口；实际记录现在还必须匹配存档生成器，避免同种子 RWG/RWG2 串用记录。
- 开放村庄、要塞、矿井、Roguelike、Thaumcraft 与原版刷怪笼地牢候选。村庄按 RWG2 的地形可行性检查和已注册的 VillageNames 规则判断；各生成器读取自己的 ConfigRWG。刷怪笼查询分段执行，使用独立 RWG2 地表、普通洞穴、火山岩浆室和熔岩洞室的方块回放，继续排除湖泊随机分支和邻近地图结构；仍是可能位置，未重放全部人口装饰、事件与战利品随机序列。
- 更正旧版记录：RWG2 的实际世界构造入口固定启用 LandmarkDecorations，不受 WorldInfo 的结构开关关闭影响；VillageNames 独立神殿仍受该开关控制。
- Jungle Canyon 改为 `#718478`，Oasis 为 `#52A46B`，Lush Desert 为 `#B8AF61`，Cold Forest / Cold Taiga 为 `#31554A`，寒冷平原与雪林用浅灰绿，雪沙漠为 `#E4ECEA`。自定义颜色覆盖继续优先。
- 协议升至 **29**，防止新结构请求落到旧 Worker；缓存标识升至 **map-v26**。两端需要同时更新，发布目录仍覆盖原来的两个 v0.4.0 文件名，内部版本为 0.4.3。

新增回归覆盖分阶段加载顺序、精细图原子替换、缓存复用、旧 RWG 不进入粗预览、宽视野资源加载、两个请求的并发上限，以及取消请求晚到时的缓存隔离。原始 RWG2 对照增加 100 个区块的地下方块数组（共 6,553,600 个位置）和村庄地形可行性检查；方块对照关闭普通洞穴，保留 RWG2 山脉河道、熔岩洞与火山室，与原始入口独立比较。完整 Forge 模组事件与实际存档验收不包含在这项对照内。

v0.4.3 发布检查：Viewer 199 项（189 通过、10 个开发工具项跳过），Worker 104 项全部通过；本次提供了本地 GregTech 参考 JAR，矿脉与流体参考项目未跳过。协议、安装映射、格式和发布构建检查通过。

## 配色、海洋性能与结构标记（v0.4.2）

运行中的本地 2.9.0 Worker 群系目录已读取核对：RWG 海洋同时携带 FOREST / SANDY 等标签；BOP Mangrove、Marsh、Tropics 也带有 OCEAN / RIVER。原配色的标签优先级导致海洋着陆地色、热带与红树林着水色。现对已核实的名称优先选色，保留其他维度配色及用户覆盖文件优先级；不会仅凭任意 OCEAN 标签把未知陆地染蓝。

| 群系 | 新默认颜色 |
| --- | --- |
| Jungle / Jungle (RWG) | 棕绿色 `#6D783F` |
| Jungle Mesa | 较暖棕绿色 `#7D7945` |
| Tropics | 绿色 `#399561` |
| Mangrove | 红树林绿 `#4F7D5B` |
| Marsh | 沼泽绿 `#5F826D` |
| Deep Ocean | 深蓝 `#213B67` |
| Cold / Temperate / Hot Ocean | 蓝色到青蓝色，保留类型区分 |
| Coral Reef / Kelp Forest | 青色 / 深青色 |

同种名称不再因原始／维度显示 ID 而偏色。另补樱花林、竹林的专用色。

性能改动：每个独立种子／噪声上下文共用 32,768 项有界群系采样缓存，复用相邻区块重叠的 21×21 混合输入；完成瓦片缓存扩至 1,024 区块。纯海洋快速路径要求全部 441 个原生混合输入都是同一基础群系的 `RealisticBiomeOcean`，并逐列排除内陆河流重分配和火山覆盖，最后仍执行熔岩洞口群系标记。海岸、岛屿或不同海洋群系交界回退完整重放；需要高度时延迟补做完整地形，不返回伪造高度。

测试基准写入 `gtnh-worker/build/reports/performance/rwg2-cache.json`，比较同一实现关闭优化后的完整重放；仅计区块计算，不包含游戏排队、通信和界面绘制。64 个相邻区块的原始群系查询由 28,224 降为 1,225（约 95.7%），海洋路径减少方块构造与表面重放；准确耗时随机器负载和 JIT 状态变化，不作为测试断言。海洋与陆地两片区域共 32,768 列与关闭优化的结果一致，并检查海洋高度按需补算。

结构查询按区块分段执行，可在游戏线程预算到期后继续；只缓存种子候选，实际方块证据每次重查。当时对世界结构开关的处理有误，v0.4.3 已按原生构造入口纠正，见上节。协议升至 28，防止新 Viewer 向旧 Worker 请求未开放的 RWG2 结构。群系编号没有变化，保留 `map-v25` 缓存语义。

## 扩展群系编号修复（v0.4.1）

本地 `fml-client-latest.log` 于 2026-11-01 10:34:01 记录 `hello` 失败：`runtime biome id is outside the Minecraft 1.7 range: 300`。Worker 当时已监听 47117；失败发生在 `getBiomeDescriptors()` 构建 Ross 128b 维度群系目录时，并非安装或端口识别错误。旧目录仍假设每个维度最多 256 个原始编号；Everglades 也存在同样限制。

本地 EndlessIDs 1.7.4 的 `ExtendedConstants` 确认为 `bitsPerBiome=16`、`biomeIDCount=65536`、`biomeIDNull=65535`。RWG2 的 `ChunkBiomeArrayCompat` 直接向 `ChunkBiomeHook.getBiomeShortArray()` 写入真实 ID，并没有把 300–329 重映射到 0–255。65535 仍是区块存储的未知值。

- `DisplayBiomeIds` 保留整个 0–65535 注册范围。合成显示编号从 65536 起，原空间维度从 131072 起，额外维度从 1048576 起；每个维度使用独立的 65536 编号区间，避免旧 3000 / 3100 / 4096 / 8192 显示编号与真实群系冲突。
- Viewer 地图缓冲、鼠标查询和群系筛选保留完整 `int` 编号，取消 `short` 截断。网络与磁盘缓存原本已使用 `int`。
- 协议升至 27，地图缓存语义前缀升至 `map-v25`，避免混用旧显示编号。两端必须配套更新。
- 回归测试覆盖完整 16 位注册范围与所有维度显示范围互不冲突；实际握手使用的目录构建器在包含 300、329、32768、65534 等编号的扩展注册表下保持名称和编号；Viewer 的真实 TCP 握手、采样、Fragment 与颜色渲染链路不截断编号。测试中的 Forge 字典按 EndlessIDs 的长度扩展，且结束后恢复。

本地交付按用户要求覆盖原有 `amidst-gtnh-biomes-v0.4.0.jar` 和 `amidst-gtnh-worker-v0.4.0.jar` 文件名；当前内部版本为 0.4.9。常规源码构建仍按内部版本命名产物。

## 已实现范围与验证

- **RWG2 Smooth** 对应真实世界类型 `RWG2_CONTINENTS`，在本地同时安装两套 RWG 时作为新地图默认项。**RWG-Alpha** 对应 `RWG`。普通 `RWG2` 模式尚未开放。
- 新建种子的世界类型选择框、种子搜索器和“设置 → 默认世界类型”提供上述选项；“世界 → 世界类型”可用同一个种子直接切换预览。原版模式继续使用自己的 Default / Large Biomes 等选项。切换预览不会改变 Minecraft 存档的生成器。
- 每张地图绑定独立生成器；协议请求、内存瓦片和磁盘缓存区分生成器及 RWG2 有效噪声模式。实际区块覆盖预测、出生点和准确性验证均核对种子与生成器，避免把另一个生成器的同种子存档当作证据。
- `StoredBiomeAccess` 读取 EndlessIDs 的 short 群系数组；255 是有效扩展 ID，65535 是未知值。没有扩展接口时兼容旧 byte 数组。读取未知记录不会调用群系管理器补写。
- RWG2 使用已注册的生物群系、Support 列表和生效配置。对 2.0.6 管理器及生成器做字节码指纹核对，独立构造种子上下文，重放混合地形、山脉河道、表面群系覆盖与熔岩洞口标记。不会调用真实区块生成、装饰或加载入口；不会更新 RWG2 存档噪声状态、共享粘土配色或发送表面替换事件。
- RWG2 已开放海底神殿、自然暮色门／下界门、洞室及普通 LootGames、史莱姆岛、AE2 陨石标记。v0.4.3 已接入村庄、要塞、矿井、Roguelike、Thaumcraft 与原版刷怪笼地牢候选，保持原有预测置信度限制。出生点仅显示匹配的已加载存档记录，纯种子出生点估算尚未接入。旧 RWG 的原有结构和出生点逻辑保留。
- 当前指纹只接受本地 **RWG2 2.0.6**。后续版本需要重新对照，不能据此宣称所有 2.9.x 均已验证。完整的模组加载列表/通用能力协商仍属于后续方案；本次按本地两套 RWG 共存环境交付。

v0.4.2 基线验证：Viewer 194 项（184 通过、10 个开发工具项跳过）；Worker 104 项（100 通过、4 个未提供 GregTech 参考输入的项目跳过），均无失败。配套 `assembleRelease`、协议一致性和 Worker 安装映射检查通过。

`InstalledRwg2ReferenceTest` 读取本地原始 JAR，在测试环境中只适配 Minecraft 字段/方法名称，并移除参考入口的无关结构调用和最终区块分配；保留原生最终群系写入顺序。Perlin / OpenSimplex、种子 0 / -47、正负坐标、火山中心与边缘、熔岩洞口周边共 **25,600 列**与独立预览相同，其中 **20,195 列**使用扩展 ID。测试为独立 Blocks 类提供互不相同的方块身份及材质，避免未初始化 Forge 注册表下全部方块为 null 的退化对照，并保持真实游戏注册表不变。额外对照原生河道中心辅助方法和结构选择哈希。测试同时检查游戏噪声开关、已保存噪声数据的 dirty 状态及共享配色未被预览更改，以及生成器/噪声模式缓存隔离。该测试使用 RWG2 基础支持集合与受控火山/洞口群系，并非完整 BOP/Thaumcraft 整合环境或实际存档验收。

旧 `RWG-alpha-1.5.3.jar` 参考对照：5,120 个群系/高度点、2,048 个表面回调结果及 1,224 个沙丘覆盖案例通过。1.5.2 的原参考指纹继续接受。扩展群系存储、Viewer 请求选择与并行地图绑定亦有回归测试。

## 新增结构调查（本地 2.9.0）

| 项目 | 本地配置与来源 | 已核实的生成行为 |
| --- | --- | --- |
| 海底神殿 | `VillageNames-4.5.21-GTNH.jar`；`config/VillageNames4/general.cfg` 的 `Add Monuments=true`。Et Futurum 的 `enableOceanMonuments=false` | 由 VillageNames 的独立世界生成器处理，不是 RWG2 自己新增的建筑算法；主世界且世界结构开关开启时参与生成 |
| 自然暮色门 | RWG2 `LandmarkDecorations`，本地已安装 Et Futurum 和 Twilight Forest | 山脉河道交汇处筛选后，在 Y=64 放置 2×2 门；要求现场 Y=62 为水、Y=65 为空气，避开熔岩洞；候选中先约一半不放装饰，剩余约四分之一选择暮色门 |
| 更低的 LootGames | RWG2 `LandmarkDecorations`，本地 LootGames 2.2.14 | 熔岩洞室中心的谜题主方块位于 **Y=39**；与下界门互斥，哈希分支约 3/4 小游戏、1/4 下界门。另一类河道交汇处小游戏位于 **Y=66** |

“自然暮色门”指新地形装饰时生成。这里没有发现周期性刷新或玩家拆除后自动重建的逻辑，不能将它理解成定时刷新点。

VillageNames 神殿默认区域间距 32 区块、分离参数 5、盐值 10387313；两个 `nextInt(27)` 取平均生成区域候选。该版本按群系名称匹配 deep ocean/deepocean 与 ocean/river；源码的半径参数会先右移 2 位，因此 16、29 实际检查的是中心 ±4、±7 方块，并非照搬新版原版的 16/29 方块检测。未来定位必须复现这份具体实现与实际群系，不应直接开启 Amidst 的原版神殿算法。[候选与群系判断](https://github.com/GTNewHorizons/VillageNames/blob/4.5.21-GTNH/src/main/java/astrotibs/villagenames/prismarine/monument/StructureOceanMonument.java)、[主世界生成入口](https://github.com/GTNewHorizons/VillageNames/blob/4.5.21-GTNH/src/main/java/astrotibs/villagenames/prismarine/monument/MonumentGeneratorIWG.java)。

本地 `lootgames/general.cfg` 仍开启普通小游戏地牢（`disable_dungeon_gen=false`，主世界菱形尺寸 `0|20`）。RWG2 的 Y=39 / Y=66 装饰直接放置 Puzzle Master，并未使用这份普通地牢选点逻辑，也不读取这个普通地牢生成开关。两类来源必须分开标记。概率都是满足地形、所属区块及模组条件之后的哈希分支比例，不能当作每个区块的生成率。[RWG2 装饰实现](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/2.0.6/src/main/java/rwg2/support/LandmarkDecorations.java)。

上述结构现已新增定位标记。`Rwg2StructurePredictor` 复现经指纹核对的 `findJunctionCenter` 纯采样逻辑，并以原生方法对照，复现熔岩中心归属、火山排除与两套哈希分支；VillageNames 神殿复现其区域候选及实际半径。纯种子结果为 `TERRAIN_CANDIDATE`，尤其河道装饰的 Y=62 水／Y=65 空气前置条件并未由群系预测证实。若匹配已加载世界的目标位置存在 Puzzle Master／对应门方块，标记升级为 `RECORDED`；不加载区块，缺少方块也不据此否定候选。海底神殿仍只给出候选，不假称已核验建筑。结构准确性批量报告的 RWG2 路径仍保留 `UNVERIFIED`。自然暮色门图标采用本地 Minecraft 1.7.10 的 `flower_rose.png`（虞美人），下界门采用 `portal.png` 紫色动画首帧。


## 版本与本地环境

分享对话讨论的是较早的 ShinySilver 分支。正式适配应以独立的 [Realistic-World-Gen-2 2.0.6](https://github.com/GTNewHorizons/Realistic-World-Gen-2/releases/tag/2.0.6) 为第一份参考，源码提交为 `497fc1f49dcab6032fb828a818931e2c391ff10e`。该版的包名与模组 ID 均为 `rwg2`，注册 `RWG2` 和 `RWG2_CONTINENTS` 两种世界类型，依赖 EndlessIDs。[注册入口](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/RWG.java)

[GTNH 2.9.0 版本清单](https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/1b62ef00ad679c51ab0ec67f8a8e42778a059ce6/releases/manifests/2.9.0.json)同时包含旧 RWG alpha-1.5.3 和 RWG2 2.0.6。因此不能把“GTNH >= 2.9.0”直接映射为某一种生成算法，也不能把 RWG2 普通模式当作旧 RWG。

本地参考实例为 PrismLauncher 的 `GT_New_Horizons_2.9.0_Java_17-26`。以下路径相对该实例的 `.minecraft/`；仅进行了读取。

| 项目 | 本地确认值 | 适配意义 |
| --- | --- | --- |
| 旧 RWG | `mods/RWG-alpha-1.5.3.jar` | 与 RWG2 共存；旧存档继续使用旧适配器 |
| RWG2 | `mods/rwg2-2.0.6.jar` | SHA-256 与官方发布资产一致 |
| EndlessIDs | `endlessids-mc1.7.10-1.7.4.jar`，`extendBiome=true` | 已启用 16 位群系存储 |
| Village Names | `VillageNames-4.5.21-GTNH.jar` | 旧项目参考 4.5.18 的村庄对照需要更新 |
| GregTech | `gregtech-5.09.54.265.jar` | 旧项目 5.09.54.133 参考不能代替当前资源算法验收 |
| 默认世界类型 | `config/defaultworldgenerator.cfg` 中 `RWG2_CONTINENTS` | 自动选择在本地新种子预览中应解析为大陆模式 |
| 创建世界白名单 | `flat`、`RWG`、`RWG2_CONTINENTS` | RWG2 普通模式已注册，但不在默认白名单；放进高级选项 |
| RWG2 基础群系 ID | `config/rwg2.cfg` 中连续的 300–329 | 不能截断到 8 位；完整运行时群系目录还包括附加模组 |
| 旧 RWG 基础群系 ID | `config/RWG.cfg` 中 200–237 范围内的 25 个离散值 | 两套基础群系目录应按运行时对象区分 |
| 村庄替换 | `Activate New Village Generator=true` | 仍需识别 Forge 最终选择的生成器 |
| 村庄配置 | Median=40、Range=10、Village-Free Radius=0 | 参数语义交给当前 VN 适配器读取，不能直接套用 RWG 的 24/8 |
| 旧 RWG 配置 | `Use fixed biome lists for new worlds=true` | 新世界与升级存档可能采用不同列表，旧模式也需补 1.5.3 参考 |

### RWG2 地形参数

这些是本地文件中的实际值，不应作为 Worker 内的固定常量。Worker 应读取模组已经生效的配置，并记录其环境身份。

| 配置项 | 当前值 |
| --- | --- |
| Minimum / Maximum Continent Width | 2700 / 2700 |
| Average / Minimum Ocean Width | 100 / 50 |
| Maximum Ocean Fraction | 0.4，配置描述为近似上限，不能解释为每个种子恰好 40% 海洋 |
| Minimum / Maximum Island Width | 300 / 600 |
| Island Placement Chance | 0.3 |
| Large Island Volcano Chance | 0.15 |
| Average Landmarks Per Type And Continent | 0.25 |
| Biome Placement Offset X / Z | 0 / 0 |
| Landmass Placement Offset X / Z | 0 / 0 |
| 洞穴、矿井、村庄、地下水湖、地下熔岩湖 | 全部开启 |
| 绿宝石生成、圆石巨石 | 全部开启；旧 RWG 的绿宝石生成为关闭 |

`saves/新的世界/level.dat` 中 `generatorName=RWG2_CONTINENTS`、`generatorVersion=0`、`generatorOptions` 为空；`data/rwg_data.dat` 中 `noiseImplementation=UNKNOWN`。在 2.0.6 的选择器中，`UNKNOWN` 会选择动态 Perlin 路径。这个值本身不是坏档证据；读取存档记录仍不足以证明当前内存中的动态开关状态，连接游戏后还应读取实际生效值。[噪声选择器](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/util/NoiseSelector.java)

### 可复核指纹

| 文件 | SHA-256 |
| --- | --- |
| `mods/rwg2-2.0.6.jar` | `19cc1e21238cf28253225963a24980519c747a0663a61ae8ae489a19c86e178b` |
| `mods/RWG-alpha-1.5.3.jar` | `fb5ee6c9927729c7dc82967542205084bd54bc032139502c69967b07268925fd` |
| `config/rwg2.cfg` | `1e99b7960e5e2b12fda2bd2f0f8278ee14a68b497d63e29da29b7de05dcd9d5b` |
| `config/RWG.cfg` | `8cf6eb83ff45986861d3f952e7f6d31b918e5caa84e0f8e82a3fdded21c69df9` |
| `config/endlessids.cfg` | `10f92a12cad4b04e72a99fff0c61afbb2d81c06e7d494bd6a5c42c75081e94c7` |
| `config/defaultworldgenerator.cfg` | `80e825f8ac805b28db307f59ab2b48f120e55db0d0674569288008eff39e9ecd` |

这些指纹用于标识本次参考环境。未来游戏验收还应保存完整模组版本清单、相关配置和运行时群系注册表，不能仅凭这六个文件判定整个环境相同。

## 设置的用户行为

新增 GTNH 专用的“地形生成器”设置，普通列表提供以下三项，另在高级选项提供 RWG2 普通模式：

| 显示名称 | 内部模式 | 行为 |
| --- | --- | --- |
| 自动 | `AUTO` | 已连接存档使用其实际生成器；独立预览使用 Worker 报告的整合包默认模式 |
| RWG 旧版 | `RWG_LEGACY` | 使用 `RWG` 世界类型和旧算法 |
| RWG2 大陆 | `RWG2_CONTINENTS` | 使用大陆、海洋、岛屿和新地貌规则；本地 2.9.0 的主要目标 |
| RWG2 普通〔高级〕 | `RWG2` | 使用 RWG2 的非大陆模式，仍属于新适配器 |

自动模式的默认值由 Worker 读取已加载的 Default World Generator 配置。没有可用默认值时，若只有一种受支持模式就选它；有多种则显示选择，不能在缺少证据时静默选择旧 RWG。设置保存的是用户偏好，每次打开地图还要解析为确定的生成上下文。

已连接存档的模式只读显示。手动换模式会创建独立预览，即使种子相同也不能继续覆盖该存档的已加载群系、出生点和结构证据。种子预览的地形参数首版沿用当前游戏配置；不增加会修改游戏配置的滑块，也不提供修改存档噪声模式的按钮。

状态栏显示实际解析结果，例如“RWG2 大陆 · 2.0.6 · 独立预览”或“RWG 旧版 · 当前存档”。不支持的版本、未安装的模式和缺失的接口应明确提示原因；地图选项不能只改标签而继续请求旧算法。

## 当前代码中的接入障碍

下列链接均指向本仓库源码，行号以当前基线为准。

| 位置 | 当前行为 | 需要改变 |
| --- | --- | --- |
| [GtnhMinecraftInterface](../../src/main/java/amidst/gtnh/worker/GtnhMinecraftInterface.java) | 只接受 `worldType=RWG`；创建访问器时只取种子 | 基于能力目录接入，访问器绑定完整上下文 |
| [MainWindowDialogs](../../src/main/java/amidst/gui/main/MainWindowDialogs.java)、[AmidstMenuBuilder](../../src/main/java/amidst/gui/main/menu/AmidstMenuBuilder.java) | 使用原版 `WorldType` 菜单 | 增加 GTNH 模式选择，不把 RWG2 塞进原版反射字段映射 |
| [WorldOptions](../../src/main/java/amidst/mojangapi/world/WorldOptions.java)、[RunningLauncherProfile](../../src/main/java/amidst/mojangapi/RunningLauncherProfile.java) | 没有 GTNH 生成上下文，默认启动使用随机种子和 DEFAULT | 新增独立的 GTNH 选项值对象，保留原版构造路径 |
| [BiomeWorkerServer](../../gtnh-worker/src/main/java/amidst/gtnh/worker/BiomeWorkerServer.java) | 握手只识别旧管理器，否则创建旧 RWG；请求和任意种子采样器以种子为核心 | 能力握手、上下文解析、显式模式路由，清除静默旧算法回退 |
| [SurfaceBiomeSamplers](../../gtnh-worker/src/main/java/amidst/gtnh/worker/SurfaceBiomeSamplers.java) | 固定 `rwg.world.ChunkManagerRealistic`、`parseWorldType("RWG")` | 保留旧实现，新增单独的 RWG2 实现和入口探测 |
| [RwgChunkBiomeReplay](../../gtnh-worker/src/main/java/amidst/gtnh/worker/RwgChunkBiomeReplay.java) | 重放旧地形和旧河流规则 | 新版单独重放，不能仅替换包名 |
| [AccuracyValidation](../../gtnh-worker/src/main/java/amidst/gtnh/worker/AccuracyValidation.java) | `getBiomeArray()` 后 `& 255`，将 255 当未知 | 新增扩展群系存储读取器；这是本地 2.9.0 的实际兼容问题 |
| [MapCacheIdentity](../../gtnh-worker/src/main/java/amidst/gtnh/worker/MapCacheIdentity.java) | 独立预览只加 `seed-preview`；同种子可能使用已加载存档身份 | 加入模式、噪声和来源；只有明确绑定的存档上下文可以读取其证据 |
| [SpawnSearchCache](../../gtnh-worker/src/main/java/amidst/gtnh/worker/SpawnSearchCache.java) | 以 seed + provider 标识任务 | 将生成上下文纳入键，切换模式后不得续算旧任务 |

## 生成上下文与通信

建议新增 `GtnhGenerationOptions`（用户选择）和 Java 8 兼容的共享 `GenerationContext`（解析结果）。后者至少包含：种子、生成器标识、实际世界类型、模组版本与指纹、适配器版本、噪声记录及生效模式、有效配置与注册表指纹、来源 `LIVE/PREVIEW`、存档会话身份。

采用“解析上下文，再查询”的流程：

1. `hello` 返回可用生成器、默认模式、当前存档信息和逐项能力，避免握手时强制创建旧 RWG 采样器。
2. 新的 `open_context` 请求解析种子、模式和来源，返回不可变 `contextId` 与能力状态。`AUTO` 到此必须解析完毕。
3. 群系、结构、出生点、资源、验证、缓存和导出查询都携带相同 `contextId`。Worker 验证其有效期，响应带回该标识以拒绝迟到结果。
4. 切换存档、模式或有效噪声状态后建立新上下文，取消旧任务；网络重连后重新打开上下文。失效上下文返回明确错误。

不要在共享的 `GtnhMinecraftInterface` 上增加可变的“当前模式”字段。种子搜索器和主地图可能共用该对象；应让每个地图访问器、结构生产器、出生点 Oracle、探矿和验证入口捕获自己的上下文。

新增协议字段和命令时将协议从 25 升到实施时的下一个空闲版本；若期间没有其他变更，则为 26。同步两端常量、metadata、共享 DTO 校验清单和配套发布版本。本次方案阶段不修改版本号。

## RWG2 采样的实现边界

### 先解决无副作用的初始化

2.0.6 提供 `ChunkManagerRealistic(long, boolean)`、`ChunkGeneratorRealistic(manager, seed, boolean)` 和 `getNewNoise`，便于构造计算入口。但“没有传真实 World”并不保证无副作用：

- 构造器调用 `NoiseSelector`，它访问 `RwgWorldSavedData.getNoiseImplementation()`。后者经 `loadInstance()` 可能向主世界 `MapStorage` 注册数据，并调用 `markDirty()`。[存档状态实现](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/world/RwgWorldSavedData.java)
- `NoiseGeneratorWrapper.useOpenSimplex` 是静态开关；不同实例会共享它。[动态噪声实现](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/util/NoiseGeneratorWrapper.java)
- 生成器构造时调用 `CanyonColor.init(seed)`，会改写静态颜色表。另一个种子的预览可能影响随后真实世界的地表生成。[颜色表](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/util/CanyonColor.java)

新增 `Rwg2SamplingBridge` 作为边界。先完成初始化试验，核对构造和原生回调的读写集合，再选择实现：优先采用显式注入噪声、配置和实例颜色表的纯采样接口；若上游暂未提供，则在 Worker 内维护与 2.0.6 固定源码对应的最小适配实现，并与原生 JAR 对照。不能以调用存档 setter、临时修改玩家世界配置或未经证明的全局状态恢复代替隔离。

首版不支持任意切换噪声环境：当前存档读取其真实生效状态；独立预览采用已声明的固定环境。对于 2.0.6 的合法 `UNKNOWN`，记录其明确的动态 Perlin 回退语义；无法识别的枚举值或无法安全确定的运行时状态报不支持。所有世界读取和原生回调继续在游戏线程执行。

### 单独实现最终群系重放

新增 `Rwg2SurfaceBiomeSampler` 和 `Rwg2ChunkBiomeReplay`。RWG2 普通与大陆模式共享新适配器，但上下文、缓存和测试分别区分。

2.0.6 的真实顺序包括地形采样、山脉河道开凿、各群系 `generateMapGen`、地表替换、洞穴和结构阶段、岩浆室与熔岩地貌处理，以及最后的熔岩洞口群系标记。首版应沿这个顺序审计哪些步骤改变群系，哪些改变后续回调读取的方块；只有证明不影响目标输出后才能省略。[区块生成器](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/world/ChunkGeneratorRealistic.java)

必须覆盖的差异：

- 河流替换新增 `continent >= 24f` 的内陆条件。
- 火山边缘会按原始地形、底层群系和新增方块深度选择地表回调，并可能改写基础群系。
- `markLavaCaveOpeningBiome` 在地表替换之后再次修改最终群系；仅调用 `getNewNoise` 不包含这一步。
- 高度数组与群系数组的 X/Z 排列不同，继续显式转换；返回的内部复用数组必须复制后再缓存。
- 不能直接调用真实世界的 `provideChunk`、`populate` 或结构生成入口。公开的 `replaceBlocksForBiome` 会发布 Forge 事件，也不能未经隔离直接用于预测。

计算使用临时方块数组和无存档预览环境。调试结果保留原始地貌群系、混合结果、最终群系、大陆值、河流值、基础高度及地貌标记，便于定位偏差。最初用完整区块计算建立正确性基线，再验证稀疏采样；不复用旧版的已认证快捷路径来假定新版正确。

### 16 位群系与真实证据

本地 EndlessIDs 1.7.4 的 `ChunkMixin` 字节码确认：旧 `getBiomeArray()` / `setBiomeArray()` 路径抛出 `UnsupportedOperationException`；新存储为 `ChunkBiomeHook.getBiomeShortArray()`，未知值是 `0xffff`。RWG2 也通过该接口写入群系。[RWG2 存储入口](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/world/ChunkBiomeArrayCompat.java)

新增 `StoredBiomeAccess`：优先识别扩展接口，使用 `shortValue & 0xffff`；仅在原版存储路径使用 `byteValue & 0xff`。两者分别识别自己的未知值，不把扩展存储中的 255 当作缺失。未知或未加载的记录仍为 `UNVERIFIED`，不能通过管理器补值后再当作实际证据。

此读取器同时用于准确性检查、已加载群系覆盖和缓存证据。EndlessIDs 的常规群系查询会为未知条目回填管理器结果，因此要避免查询本身改变本应只读的证据。

Viewer 的 `MapStorage` 已按 32 位整数保存群系 ID，无需仅为 16 位群系更改二进制布局。RWG 内部地貌变体 ID、16×16 的区块列数和 Minecraft 群系 ID 是不同概念，不能把所有长度 256 的数组扩大成 65536。

## 缓存与能力降级

所有内存缓存、任意种子采样器、出生点续算、磁盘身份和视图恢复都必须包含完整生成上下文。当前 `map-v23` 预测语义前缀在实施时升级；文件结构未变时无需同时改 `MapStorage.MAGIC`。

预览绑定和实际存档绑定要显式区分。同种子、不同模式不能混用已加载区块证据；同种子不同存档也不能共享实际数据。环境指纹继续后台准备，未完成时禁用持久缓存并正常查询，不阻塞首次地图加载。模组内容哈希可在后台计算；不要在游戏 tick 同步扫描 JAR 或配置目录。

首个可用版本以“模式选择 + 最终群系底图 + 16 位校验”为发布门槛。高度与地貌值先作为诊断信息，新增可视化图层后置。结构、出生点和资源按能力单独开启：

- 保存的出生点只对匹配的 LIVE 上下文显示；独立出生点估算等待新版地表与洞穴重放验证。
- 村庄按最终生成器及 VN 4.5.21 参数适配；原版 RWG2 的 `areBiomesViable` 规则也需单独核对。[群系管理器](https://github.com/GTNewHorizons/Realistic-World-Gen-2/blob/497fc1f49dcab6032fb828a818931e2c391ff10e/src/main/java/rwg2/world/ChunkManagerRealistic.java)
- 矿井、要塞、原版地牢及依赖地形的其他结构逐项验证，不能自动启用旧 RWG 的全部预测。
- GT 矿脉与地下流体优先复用模块，但使用当前 5.09.54.265 运行环境重新验证。候选结果继续与实际矿石区分。
- 下界、末地和太空维度保持独立算法；审计创建预览世界时硬编码 `RWG` 的入口，避免只安装 RWG2 时受连带影响。

未支持的图层在界面标明原因，不能将空结果显示成“该区域不存在”。地图底图不因某一个结构适配失败而整体失效。

## 逐文件改动清单

路径相对于仓库根目录。新增类名为建议；同一职责不拆成多份重复实现。

| 优先级 | 文件或模块 | 改动与验收 |
| --- | --- | --- |
| P0 | Worker 新增 `StoredBiomeAccess`；`AccuracyValidation`、`SurfaceBiomeSamplers`、`MapCacheIdentity` | 实际群系读取统一支持 EndlessIDs，不调用被禁止的字节数组接口 |
| P0 | Worker 新增 `WorldGeneratorRegistry`、`Rwg2SamplingBridge` | 探测旧 RWG / RWG2、模式和接口；证明初始化不写存档、不污染真实世界噪声与颜色表 |
| P0 | Viewer / Worker 新增共享 `GenerationContext`，Viewer 新增 `GtnhGenerationOptions` | 明确 LIVE/PREVIEW 与不可变上下文；共享 DTO 保持 Java 8 兼容 |
| P1 | `GtnhBiomeSource`、`GtnhBiomeWorkerClient`、`GtnhWorkerInfo`、Worker `BiomeWorkerServer` | 能力握手、默认模式和上下文协议；所有查询传递上下文 |
| P1 | `GtnhMinecraftInterface`、`WorldOptions`、`RunningLauncherProfile`、`WorldBuilder` | 保留模式到访问器、结构、出生点和资源入口，不只传种子 |
| P1 | `AmidstSettings`、`Actions`、`MainWindowDialogs`、`AmidstMenuBuilder`、`SeedSearcherWindow` | 新增独立 GTNH 设置；普通选项为自动 / 旧 RWG / RWG2 大陆；搜索器同步使用上下文 |
| P1 | `CommandLineParameters`、`GtnhWorkerSettings`、中英文文案 | 提议新增 `-gtnh-generator auto\|rwg\|rwg2\|rwg2-continents`，不改变原版 `-world-type` 的语义 |
| P1 | Worker 新增 `Rwg2SurfaceBiomeSampler`、`Rwg2ChunkBiomeReplay` | 完整最终群系路径，禁用未经验证的稀疏优化 |
| P1 | `BiomeWorkerServer.BiomeTileKey`、`OverworldTileCache`、`SpawnSearchCache`、`MapCacheIdentity`、Viewer `ViewerFacade` / `MapViewState` | 模式、噪声、来源和会话隔离，迟到请求不得污染新视图 |
| P1 | Worker `RwgRuntimeClasses`、`RwgSurfaceEffects`、`RwgTerrainAccess` | 若新适配器使用字节码认证，明确分开 `rwg.` 与 `rwg2.`；旧证书不得用于新版 |
| P2 | `RwgStructurePredictor`、`VillageGenerationRules`、`VanillaDungeonPredictor`、`RwgSpawnCaves` | 消除从旧 `rwg.config.ConfigRWG` 读取新版开关的可能，分别启用已验证能力 |
| P2 | `NetherBiomeSampler`、`SpaceDimensionSampler`、资源及其他结构模块 | 传递正确上下文，清理构造预览世界时的旧 RWG 硬依赖；做受影响回归 |
| 发布 | 根与 Worker `build.gradle.kts`、`verify-release-contract.gradle.kts`、metadata、Worker `@Mod` | 新增 RWG2 / EndlessIDs 参考输入与共享 DTO 检查，升级配套协议和发行版本 |
| 发布 | `docs/usage.md`、新发行说明、`CONTRIBUTING.md`、`BUILDING.md` | 写清选择方式、已验证版本、功能能力和参考验证命令 |

## 实现顺序与验收

### 第一批 环境与只读边界

- [ ] 固定本地 2.9.0 与 RWG2 2.0.6 为首批参考；整理旧 RWG 1.5.2 / 1.5.3 的回归输入。
- [ ] 实现扩展群系读取器，覆盖 255、256、300、329、32768、65534 和未知 65535；原版 255 仍为未知。
- [ ] 完成 RWG2 初始化隔离试验；调用前后检查 MapStorage 注册、dirty 状态、动态噪声开关、静态颜色表和已加载区块集合。
- [ ] 在新生成上下文中识别本地默认大陆模式、两模组共存和实际存档模式。

这一批的阻塞条件是初始化是否能保持只读。失败时继续缩小或隔离计算入口，不用完整区块生成绕过。

### 第二批 设置与底图

- [ ] 实现上下文协议、界面选择、命令行和种子搜索器；支持仅旧 RWG、仅 RWG2、两者共存三种环境。
- [ ] 完成 RWG2 普通和大陆模式的最终群系重放；默认优先交付大陆模式。
- [ ] 同种子切换旧 RWG / RWG2 / 大陆模式时结果和缓存分离；PREVIEW 不覆盖 LIVE 的已加载数据。
- [ ] 关闭重开、切换存档、连接重试、取消和迟到响应均不会恢复错误模式。
- [ ] 首次无缓存、有缓存、缓存不可写和配置指纹慢准备时都能显示地图；保留游戏暂停和 tick 预算回归。

### 第三批 独立准确性验证

- [ ] 使用原生 JAR 校验中间采样值，并在独立测试世界中采集真实生成的群系数组；不能只比较两个共享同一重放代码的实现。
- [ ] 覆盖海岸、河口、山脉河道、火山中心与边缘、熔岩洞口、气候边界、负坐标和跨区块位置。
- [ ] 覆盖 0、正负种子、64 位边界种子；同种子以不同区块请求顺序查询，结果应一致。
- [ ] 覆盖本地默认配置及非零迁移偏移；分别验证可用噪声模式，记录 UNKNOWN 的实际解析结果。
- [ ] 测试世界的已记录样本以最终 Minecraft 群系 ID 逐点相等为底图通过条件；未知证据不进入准确率分母。
- [ ] 对验证过程中未加载区域保持 `UNVERIFIED`，不得为了核对结果自动加载或生成区块。

### 第四批 结构与发布

- [ ] 逐项启用村庄、矿井、要塞、出生点、地牢、资源；每项注明候选 / 实际记录的边界。
- [ ] 新增 `rwg2ReferenceJar`、`endlessIdsReferenceJar` 等参考输入并贯通根构建与 Worker；缺失时标记跳过，不声称对照已通过。
- [ ] 相关测试通过后运行配套 `assembleRelease`，完成真实 2.9.0 游戏验收再发布。
- [ ] 后续 2.9.x 记录实际模组版本和配置，未知实现先提示未验证；不能将一个版本通过写成所有未来版本已兼容。

建议测试类包括 `StoredBiomeAccessTest`、`WorldGeneratorRegistryTest`、`GenerationContextTest`、`Rwg2SurfaceBiomeSamplerTest`、`Rwg2ChunkBiomeReplayTest`、`InstalledRwg2ReferenceTest`；同时扩展现有 `BiomeWorkerServerTest`、`GtnhBiomeWorkerClientTest`、`GtnhMinecraftInterfaceTest`、`AccuracyValidationTest`、`OverworldTileCacheTest` 和 `SpawnSearchCacheTest`。这些是完整后续计划的建议入口；实际已完成的测试以本文开头的验证记录为准。
