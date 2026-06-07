# NanoLimbo Enhanced

NanoLimbo Enhanced 是面向技术爱好者的 NanoLimbo 扩展版主程序。它在轻量 Minecraft Limbo Server 基础上，内嵌了 **Java 源码版哪吒 Agent**、Cloudflare Tunnel Runtime 和 WebSocket 代理/订阅能力，目标是把常用自托管实验组件打包到一个 Java 项目里，方便构建、部署和二次开发。

> 本项目适合个人学习、Minecraft 协议研究、自托管实验和技术爱好者折腾场景。请遵守服务商条款和当地法律法规，不要提交真实 token/key 到公开仓库。

## 功能概览

- **NanoLimbo 核心**
  - 轻量 Minecraft Limbo Server
  - 支持 MOTD、Title、BossBar、玩家列表、维度配置
  - 支持 Velocity / BungeeGuard 信息转发校验
- **Java 源码版哪吒 Agent**
  - 通过 `com.nezhahq.agent.NezhaJavaAgent` 内嵌运行
  - 无需额外下载哪吒 agent 二进制
  - 由 `NezhaAgentBridge` 组装配置并启动
- **WebSocket 代理与订阅**
  - `App.java` 提供 HTTP / WebSocket 服务
  - 支持 VLESS、Trojan、Shadowsocks 风格连接解析
  - 支持生成 base64 订阅内容
- **Cloudflare Tunnel Runtime**
  - 通过 Java 版 tunnel runtime 启动固定隧道
  - 可生成 tunnel 订阅节点
- **自动构建**
  - GitHub Actions 推送到 `main` 后自动构建 `server.jar` 并上传 Release

## 项目结构

```text
.
├── build.gradle                         # Gradle / Shadow Jar 构建配置
├── settings.gradle                      # Gradle 项目名
├── README.md                            # 当前说明文档
├── .env.example                         # 旧环境变量示例，仅作参考，不参与 Java 主线配置
├── .github/workflows/build-jar.yml      # GitHub Actions 构建 server.jar
├── legacy/komari/                       # 旧 Komari 实验文件归档
├── src/main/java/
│   ├── App.java                         # WebSocket 代理、订阅、哪吒、Tunnel 主运行逻辑
│   ├── HardcodedConfig.java             # 硬编码运行配置
│   ├── NezhaAgentBridge.java            # Java 源码版哪吒 Agent 配置桥接
│   ├── SubscriptionComposer.java        # 订阅内容生成
│   ├── TunnelSupport.java               # 反射调用 Tunnel Runtime
│   ├── com/nezhahq/agent/               # Java 源码版哪吒 Agent 与 proto 代码
│   ├── com/nezhahq/agent/tunnelruntime/ # Java Cloudflare Tunnel Runtime
│   └── ua/nanit/limbo/                  # NanoLimbo 核心和协议处理
└── src/main/resources/
    ├── settings.yml                     # NanoLimbo 默认配置
    └── dimension/*.snbt                 # Minecraft 维度 codec
```

## 环境要求

- JDK 21
- Gradle Wrapper 已包含在仓库中
- Linux 环境更适合部署；Windows 主要用于开发/编译

## 快速开始

### 1. 编辑硬编码配置

本项目当前主线采用硬编码配置，直接编辑：

```text
src/main/java/HardcodedConfig.java
```

至少需要按需填写：

```java
static final String UUID = "你的-UUID";
static final String NEZHA_SERVER = "你的哪吒服务端:443";
static final String NEZHA_KEY = "你的哪吒Agent密钥";

// 如果启用 Cloudflare Tunnel
static final boolean TUNNEL_ENABLED = true;
static final String TUNNEL_TOKEN = "你的CloudflareTunnelToken";
static final String TUNNEL_DOMAIN = "你的固定隧道域名";
```

[.env.example](.env.example) 仅保留为旧环境变量参考，Java 主线不会从 `.env` 读取这些代理/哪吒配置。

### 2. 构建

```bash
./gradlew clean shadowJar
```

构建产物位于：

```text
build/libs/*.jar
```

### 3. 独立 Jar 运行

```bash
java -jar build/libs/NanoLimbo-*.jar
```

独立运行时入口为：

```text
ua.nanit.limbo.NanoLimbo
```

## 关键配置

### NanoLimbo 配置

默认配置文件：

```text
src/main/resources/settings.yml
```

首次运行时会复制到运行目录下的 `settings.yml`。主要配置包括：

- `bind.ip` / `bind.port`
- `maxPlayers`
- `ping.description`
- `dimension`
- `brandName`
- `joinMessage`
- `bossBar`
- `title`
- `infoForwarding`
- `traffic`

### Java WS / 哪吒 / Tunnel 配置

运行配置由 `HardcodedConfig.java` 中的硬编码常量控制。

常用配置项：

| 配置项 | 说明 |
|------|------|
| `UUID` | VLESS/Trojan/SS 使用的 UUID |
| `DOMAIN` | 对外展示域名，留空时尝试获取公网 IP |
| `SUB_PATH` | 订阅路径 |
| `WSPATH` | WebSocket 路径，留空时使用 UUID 前 8 位 |
| `NEZHA_SERVER` | 哪吒服务端地址 |
| `NEZHA_PORT` | 哪吒服务端端口，可选 |
| `NEZHA_KEY` | 哪吒 Agent 密钥 |
| `DOH_ENABLED` | 是否启用 DoH |
| `TUNNEL_ENABLED` | 是否启用 Cloudflare Tunnel Runtime |
| `TUNNEL_TOKEN` | Cloudflare Tunnel token |
| `TUNNEL_DOMAIN` | 固定隧道域名 |
| `PORT` / `SERVER_PORT` | Minecraft 服务端端口 |

## 自动构建 server.jar

仓库包含 GitHub Actions：

```text
.github/workflows/build-jar.yml
```

使用方式：

1. Fork 本项目
2. 在 Actions 页面启用 workflow
3. 推送到 `main` 或手动触发 workflow
4. 等待构建完成
5. 在 Release 中下载 `server.jar`

## 关于 legacy/komari

`legacy/komari/` 目录保存旧的 Komari 实验脚本和文档。当前 Java 主线不是 Komari，而是 **Java 源码版哪吒 Agent**。这些文件仅作历史参考，默认不参与 Java 构建。

## 安全提示

- 不要把真实 `NEZHA_KEY`、`TUNNEL_TOKEN` 提交到 Git。
- 如果密钥曾经提交到公开仓库，请立即轮换。
- 外部服务 token 请只填写在自己的私有源码/私有仓库中，不要提交到公开仓库。

## 开发建议

- 修改 NanoLimbo 行为：优先看 `src/main/java/ua/nanit/limbo/`。
- 修改 WebSocket/订阅/哪吒/Tunnel 行为：优先看 `App.java`、`HardcodedConfig.java`、`NezhaAgentBridge.java`、`TunnelSupport.java`。
- 修改默认配置：NanoLimbo 看 `src/main/resources/settings.yml`；代理/哪吒/Tunnel 看 `src/main/java/HardcodedConfig.java`。
- 修改构建发布：看 `build.gradle` 和 `.github/workflows/build-jar.yml`。
