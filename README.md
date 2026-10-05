# RABE City · Key Curator 四端版

基于你提供的新版城市/车内视角项目修改。包含 Java 源码、前端源码、已编译 JAR、JPBC 依赖、启动脚本及回归测试。**不需要 Maven、Node.js、数据库或联网下载前端依赖。**

## 这次修改

- 已替换为用户提供的 **Pastel Canal City Map**（1672×941），整张地图按原比例显示；新增 12 条沿道路、桥梁和公园外围的闭合路线。已注册车辆按固定 ID 分配区域、方向和起点，不会因刷新或增加车辆重新排布。地图说明及验证见 [地图与路线](docs/MAP_ROUTES.md)。
- 新增 **D：Key Curator**，只有车辆注册与注销管理操作。A/C 生成自己的私钥，D 只登记公钥。
- 注册采用 Hohenberger 等（2023）**第 6 节 Construction 6.1 的分层编译方法**：计数器、1/2/4/… 槽位、D1/D2、满层聚合、MSB 路由；不再等待全部预设车辆同时注册。
- C 的明文按车辆 ID 分别保存。全局状态不返回明文；切车立即清空显示，异步解密结果不会出现在另一辆车上。
- A/B/C/D 共用 B 的仿真时钟；晚打开页面、刷新页面、全城暂停/继续时保持同一运动进度。
- 保留男女驾驶员车内视角、双向策略、指定密文撤销和真实泄露密钥追踪。

第 6 节本身不定义注销。D 的注销使用原项目的 `Scheme.deregister`，并更新相关分层公钥与辅助项；与 A 的“撤销某辆车对某一条密文的权限”是两个不同操作。算法对应与工程调整见 [注册实现说明](docs/REGISTRATION.md)。

## 最快运行：一台电脑启动四个端

准备 **Java 8 或以上**。源码编译和测试需要 JDK；直接运行已有 JAR 只需要 Java 运行环境。先在终端执行 `java -version` 检查。

### Windows

解压整个文件夹，双击 **`start-local.bat`**。它会在首次启动时生成配置，再打开四个 Java 窗口。保留这些窗口。

### macOS / Linux

在项目根目录打开终端：

```sh
sh start-local.sh
```

保留终端；按 `Ctrl+C` 停止本次启动的四个端。日志在 `runtime/logs/`。

### 打开页面

| 节点 | 本机浏览器地址 | 用途 |
|---|---|---|
| D · Key Curator | http://localhost:8083 | **先在这里注册车辆**，也在这里注销 |
| A · 拥有者 | http://localhost:8081 | 加密发送、指定密文撤销 |
| B · 云服务器 | http://localhost:8080 | 存储、转换、追踪、统一仿真时钟 |
| C · 数据用户 | http://localhost:8082 | 本地解密、车辆专属明文、泄露样本 |

**首次启动城市中没有车辆，这是正常的。** 等待 D 公共参数生成完成，按下面步骤添加车辆。不再自动生成旧版那批固定编号车辆。

1. D 输入名称，角色选“数据拥有者”，属性填 `特斯拉,新能源,授权通行`（依次绑定 A1、A2、A3；已有名称复用原编号），点击注册。A 在线后自动生成私钥，D 校验完成后车辆出现。
2. D 再注册一辆“数据用户”，同样填 `特斯拉,新能源,授权通行`；再注册一辆只有 `特斯拉,授权通行` 的用户车，用于拒绝实验。
3. A 选择拥有者车辆，策略设 `A1 AND A2`，输入文字并发送。
4. C 选择拥有 `A1,A2,A3` 的用户车，发送方验证策略设 `A1 AND A2`，点击解密。该车的“本地明文”显示恢复文字。
5. C 切到只有 `A1,A3` 的用户车：收件箱为空，解密该消息被拒绝。新注册车辆不能解密其注册前产生的消息；需要在 A 重新发送。
6. D 选中一辆用户车并注销。该车在线解密请求被拒绝；A 之后发送的新密文还会受到 `Deregister` 聚合项更新保护。
7. C 可复制真实私钥作为泄露样本；B 执行 Trace，显示全局注册身份 `i′`。

D 显示“排队等待 A/C”时，需要相应端运行。注册会按提交顺序处理，避免两个申请争用同一注册计数器。

## 分布式运行：A/B/C/D 四台电脑

也可以把 D 与现有一台电脑放在一起，仍运行四个独立进程；端口不同即可。

假设 B 的局域网 IP 为 `192.168.1.20`，D 为 `192.168.1.40`，在任意一台电脑的项目根目录**只生成一次配置**：

```sh
java -jar dist/rabe-city.jar prepare config/lan http://192.168.1.20:8080 128 50 http://192.168.1.40:8083
```

参数 `128 50` 分别是累计注册容量和属性数量；容量向上取 2 的幂。把完整项目复制到每台电脑，再分别放入该节点的配置文件：

- A：`config/lan/owner.properties`
- B：`config/lan/cloud.properties`
- C：`config/lan/user.properties`
- D：`config/lan/curator.properties`

**四端必须使用同一次 `prepare` 生成的配置**，否则令牌不匹配。不要在每台电脑上各自重新生成一套。只分发本机对应的配置文件。

先启动 D 和 B，再启动 A、C：

```sh
# 各自在对应电脑执行其中一条
java -Xmx2g -jar dist/rabe-city.jar config/lan/curator.properties
java -Xmx2g -jar dist/rabe-city.jar config/lan/cloud.properties
java -Xmx2g -jar dist/rabe-city.jar config/lan/owner.properties
java -Xmx2g -jar dist/rabe-city.jar config/lan/user.properties
```

B 的 TCP 8080 和 D 的 TCP 8083 需要允许实验局域网连接。**每台电脑在自己的浏览器访问 localhost 对应端口**，不要在 A 的浏览器打开 D 的 IP 代替 D 本地控制台。浏览器管理 API 仅允许回环地址；机器间访问使用带令牌的 `/node/` API。

## 100 辆规模

默认 128 个身份、50 个属性槽位（A1–A50）。Setup 固定槽位，Reg 按输入顺序分配名称，四端可查看持久化字典。旧的 16/8 部署不能直接复用公共参数；升级时请将四端 data 指向新的独立目录，保留旧目录以供回退。本地现有配置已切换到 runtime/v2/。新部署示例：

```sh
java -jar dist/rabe-city.jar prepare config/experiment http://B的IP:8080 128 50 http://D的IP:8083
```

`128` 是**累计分配的身份数上限**，包括已经注销的车辆；注销不回收编号。D 可在地图上选中已注销车辆，点击“让当前车辆重新加入”，恢复原身份、属性及密钥，不消耗新名额；其他已注销车辆和密文级撤销保持有效。D 中按需注册至少 100 辆。大规模的分层 CRS、各层公钥交叉项校验会显著增加首次初始化和注册时间。当前版本上限 128，不支持运行中扩容；不要直接更改旧运行目录的容量。

## 初始化加速

交叉项生成使用共享的 8-bit 固定基底预计算表（小规模使用 5-bit），按槽位并行计算，最多使用 4 个线程，并为系统保留一个逻辑处理器。利用 `A_i = g^t_i` 将 `A_i^u` 等价计算为 `g^(t_i*u mod p)`；属性数、密码参数、输出格式不变，Setup 指数不会保存到公共参数文件。D 页面显示当前层交叉项完成行数和百分比，写盘时单独显示保存状态。

已有完整参数直接加载。更新 JAR 不会改变已运行的 JVM；正在初始化时建议让当前层完成后再重启，未完成的一层仍需重新生成。

可复现的新旧算法对比（同一随机输入，逐字节检查所有输出，包含单线程与多线程）：

```sh
mkdir -p build/setup-tests
javac -encoding UTF-8 -cp 'dist/rabe-city.jar:lib/*' -d build/setup-tests tests/SetupChecks.java
java -Xmx2g -cp 'build/setup-tests:dist/rabe-city.jar:lib/*' rabe.SetupChecks 32
```

本机 16 槽位 × 50 属性测试：原实现 8.67 秒，优化后 4 线程 1.85 秒（约 4.7 倍）。32 槽位 × 50 属性复测：39.56 秒降至 10.24 秒（约 3.9 倍），51,200 个数组位置逐字节一致。这是交叉项生成的样本测量，不含各层其余参数生成和磁盘写入，不代表 128 槽位的固定加速比。四节点回归通过 239 项接口检查及 8,265 项密码运算检查。

## 保存、重启与旧数据

- 直接重新启动相同配置即可恢复。A/C 保存私钥和各车收件箱，B 保存密文、样本、运动时钟，D 保存计数器、公钥、辅助块和队列。
- **不要将旧版 `runtime/` 或旧版配置复制到这个版本**，注册数据结构和令牌分工已改变。这次项目包不附带任何旧运行私钥。
- 重做一套独立实验时，把原 `runtime/` 和配置目录完整备份到其他位置，再生成新配置。不要仅重置其中一个端。
- 若端口占用，先关闭上一次启动的进程，或在配置中一致修改端口和节点 URL。

## 源码与测试

```sh
# macOS/Linux 编译
sh build.sh
# Windows 编译
build.bat

# JDK + Python 3，四个真实 JVM 的自动回归（测试目录必须是新目录）
python3 tests/integration.py --output /你的路径/rabe-test-run --base-port 8280
```

测试不修改项目运行目录，完成后自动停止自己启动的节点。Windows 可以使用 `python` 命令。结果保存在指定目录的 `report.json`、`crypto.log` 和各节点日志。

已完成本机四进程回归与浏览器验证，具体范围和结果见 [测试报告](docs/TEST_REPORT.md)。这次回归不等同于重新完成原先“100 辆、1000 次循环”的全部压力验收，也没有实测四台物理电脑之间的网络。

## 文件说明

- `src/main/java/city/Curator.java`：分层注册/注销、D1/D2、持久化。
- `src/main/java/city/Compiler6.java`：密文封装、MSB 层选择、发送证明挑战。
- `src/main/java/city/Client.java`：A/C 私钥生成、加密/本地解密、按车辆分区的收件箱。
- `src/main/java/city/Cloud.java`：B 转换、存储、指定密文撤销和追踪。
- `src/main/java/city/Motion.java`、`web/city.js`：统一运动时钟及城市动画。
- `web/app.js`、`web/cockpit.js`：四端控制台与保留的车内视角。

本项目是研究演示。第 6 节的编译结构不自动修复原项目的发送方认证漏洞，也不自动赋予修改后方案原论文的安全证明；具体已知问题保留在 [SECURITY_FINDINGS.md](docs/SECURITY_FINDINGS.md)。
