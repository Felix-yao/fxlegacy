# FX遗产

一个装在安卓手机上的设备控制 App，通过家里 WiFi 直连**斐讯 R1 音箱**和**斐讯 TC1 排插**（刷了 zTC1 固件），电脑无需开机。iOS 风格界面。

> **下载**：[最新 Release](https://github.com/Felix-yao/fxlegacy/releases/latest)
> 装上直接可用 —— 首次运行会自动在局域网内发现 R1 音箱和 TC1 排插，不需要填 IP/MAC。

## 功能

> 两台设备都支持**局域网自动发现**，不用手动填地址。

### R1 音箱
- **一键打开蓝牙模式**：模拟三击顶键，让音箱进入蓝牙模式供电脑连接
- 音量控制（滑块 + 2/4/6/8/10/12 快捷按钮）
- 睡眠定时（15/30/60 分钟渐弱到静音）
- 静音休眠开关（记住原音量）
- 状态读取（固件 / WiFi 信号 / 开机时长 / 蓝牙连接状态）

原理：手机通过 WiFi 用**原生 ADB over TCP**（端口 5555）直连音箱发 shell 命令，无需 root、无需电脑。

### TC1 排插（zTC1 固件）
- 6 路独立开关，按设备物理位置排成 2×3 网格（上左/上中/上右、下左/下中/下右）
- 每路独立定时关闭（5/15/30/60 分钟）
- 总开关、实时功率、累计运行时间
- 插口改名

原理：通过 **UDP 广播**（发送 `255.255.255.255:10182`，接收 `10181`）发送 JSON 命令，协议兼容 [a2633063/SmartControl_Android_MQTT](https://github.com/a2633063/SmartControl_Android_MQTT) 的 zTC1 固件。

## 目录结构

```
.
├── AndroidManifest.xml          # 应用配置
├── build.sh                     # 一键构建脚本（无需 Gradle）
├── sign_apk.java                # v1+v2+v3 签名工具
├── src/com/r1/controller/       # Java 源码
│   ├── DeviceListActivity.java  # 设备列表主页
│   ├── MainActivity.java        # R1 音箱控制页
│   ├── Tc1Activity.java         # TC1 排插控制页
│   ├── AdbClient.java           # ADB over TCP 客户端
│   └── Tc1Client.java           # zTC1 UDP 客户端
├── res/                         # 布局 / drawable / 图标 / 字符串
│   └── values/device.xml        # 设备默认地址（自动发现失败时才用）
└── tools/                       # 构建工具（aapt2 / android.jar / r8 / apksig）
```

## 构建

需要 **JDK 17+**（`javac`/`keytool` 在 PATH，或设置 `JAVA_HOME`）和 **Python 3**。在 Windows Git Bash 下：

```bash
bash build.sh
```

输出：`apk/FX遗产.apk`

构建脚本会在首次运行时自动生成签名密钥 `r1app.jks`（已被 `.gitignore` 排除，不要提交）。**用自己的密钥签名后，后续更新必须继续用同一个密钥才能覆盖安装。**

## 设备地址：自动发现

**通常什么都不用配。** App 首次运行会自动在局域网里找设备：

- **R1 音箱**：并发扫描本网段的 5555 端口（ADB），找到就自动记住
- **TC1 排插**：发一条 UDP 广播，设备会回报自己的 MAC，App 自动采用

找到后的地址存在本地，之后直接连，不再扫描。IP 变了也会自动重新找（连不上时才扫，不拖慢启动）。

R1 页右侧还有一个「查找」按钮，可以随时手动触发一次扫描。

### 自动发现失败时

如果路由器隔离了广播、或设备不在同一网段，可以改 `res/values/device.xml` 兜底：

```xml
<string name="r1_ip">192.168.1.100</string>      <!-- 你的 R1 音箱 IP -->
<string name="tc1_mac">001122334455</string>      <!-- 你的 TC1 MAC（小写无冒号） -->
```

改完重新 `bash build.sh`。R1 的 IP 也可以在 App 内「音箱地址」处直接填。

## zTC1 协议速查

| 操作 | UDP 广播内容（发往 :10182） |
|------|------------------------------|
| 打开插口 N | `{"mac":"<mac>","plug_N":{"on":1}}` |
| 关闭插口 N | `{"mac":"<mac>","plug_N":{"on":0}}` |
| 改名 | `{"mac":"<mac>","plug_N":{"setting":{"name":"名字"}}}` |
| 查询状态 | `{"mac":"<mac>","plug_0":{"on":null,...},...,"power":null,"total_time":null}` |

设备回包发到手机 `10181` 端口，格式为 JSON（含 `plug_0..5`、`power`、`total_time`、`version` 等字段）。

## 许可

MIT
