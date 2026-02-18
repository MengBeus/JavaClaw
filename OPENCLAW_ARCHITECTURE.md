# OpenClaw 完整架构分析

## 整体架构概览

OpenClaw 是一个 **本地优先的单用户 AI 助手**，采用 monorepo 结构（pnpm workspace），核心用 TypeScript/Node.js (≥22) 编写。

架构可以分为 **5 个核心层**：

---

## 1. Gateway（网关层 — 控制平面）

`src/gateway/` 是整个系统的心脏，WebSocket 服务运行在 `ws://127.0.0.1:18789`。

- **职责**：统一协调所有 sessions、channels、tools、events
- **关键模块**：
  - `server.ts` / `server.impl.ts` — WS 服务核心
  - `auth.ts` / `device-auth.ts` — 认证与设备授权
  - `agent-prompt.ts` — Agent 提示词构建
  - `chat-attachments.ts` / `chat-sanitize.ts` — 消息处理管道
  - `config-reload.ts` — 热重载配置
  - `openai-http.ts` / `openresponses-http.ts` — 兼容 OpenAI API 的 HTTP 端点
  - `protocol/` — WebSocket 协议定义
  - `server-methods/` — RPC 方法实现
- **特点**：单进程、单用户，所有客户端（CLI、WebChat、macOS app、移动端）都连接到这个 Gateway

---

## 2. Channels（消息通道层）

`src/channels/` + 各平台独立目录（`src/whatsapp/`、`src/telegram/`、`src/discord/`、`src/slack/`、`src/signal/`、`src/imessage/`、`src/line/`）

- **职责**：将不同平台的消息统一抽象为内部格式
- **支持平台**：WhatsApp (Baileys)、Telegram (grammY)、Slack (Bolt)、Discord (discord.js)、Signal (signal-cli)、iMessage (BlueBubbles)、Google Chat、Teams、Matrix、Zalo、Line、WebChat
- **关键模块**：
  - `registry.ts` — 通道注册中心
  - `session.ts` — 会话管理
  - `command-gating.ts` / `mention-gating.ts` — 消息过滤与权限控制
  - `allowlists/` — 白名单机制
  - `plugins/` — 通道级插件

---

## 3. Agent Runtime（Agent 运行时）

`src/agents/` + `src/routing/` + `src/providers/`

- **Pi Agent Runtime**：以 RPC 模式运行，支持 tool streaming 和 block streaming
- **多 Agent 路由**：消息可路由到不同的隔离工作区
- **Provider 抽象**：`src/providers/` 支持多模型后端（Anthropic、OpenAI 等）
- **会话模型**：
  - DM 直聊 — 一对一
  - Group 群聊 — 隔离模式，每个群独立 session
  - 激活模式 / 队列模式 — 控制 agent 何时响应

---

## 4. Tools & Capabilities（工具与能力层）

分布在多个目录中：

| 模块 | 路径 | 功能 |
|------|------|------|
| Browser | `src/browser/` | 专用 Chrome/Chromium 自动化控制 |
| Canvas | `src/canvas-host/` + `vendor/a2ui/` | Live Canvas，A2UI agent 驱动的可视化工作区 |
| Cron | `src/cron/` | 定时任务 |
| Hooks | `src/hooks/` | Webhooks |
| Media | `src/media/` + `src/media-understanding/` | 图片/音频/视频处理与理解 |
| TTS | `src/tts/` | 文字转语音 |
| Memory | `src/memory/` | 记忆系统（插件槽位，同时只运行一个实现） |
| Link | `src/link-understanding/` | 链接内容理解 |
| Plugins | `src/plugins/` + `src/plugin-sdk/` | 插件系统与 SDK |
| Skills | `skills/` | 技能注册（ClawHub） |

---

## 5. Client Apps（客户端层）

`apps/` + `src/cli/` + `src/tui/` + `ui/`

- **CLI**：`src/cli/` + `src/commands/` — 命令行入口，terminal-first 设计
- **TUI**：`src/tui/` + `src/terminal/` — 终端 UI
- **WebChat**：`ui/` — Web 聊天界面
- **macOS App**：`apps/macos/` — 原生 macOS 客户端
- **iOS**：`apps/ios/` — iOS 客户端
- **Android**：`apps/android/` — Android 客户端
- **共享库**：`apps/shared/OpenClawKit/` — 跨平台共享代码

---

## 数据流（请求生命周期）

```
用户消息 (WhatsApp/Telegram/Slack/...)
       │
       ▼
  Channel Adapter ── 消息标准化、权限检查、配对验证
       │
       ▼
  Gateway (WS)  ──  会话路由、状态管理、配置
       │
       ▼
  Agent Router  ──  选择目标 agent/workspace
       │
       ▼
  Pi Agent Runtime (RPC)  ──  调用 LLM Provider
       │
       ├──▶ Tool Calls (browser/canvas/cron/media/...)
       │         │
       │         ▼
       │    Tool Results 回传
       │
       ▼
  Response Stream  ──  block streaming 回到 Gateway
       │
       ▼
  Channel Adapter  ──  格式化为平台消息
       │
       ▼
  用户收到回复
```

---

## 安全模型

- **默认 DM 配对**：未知发送者需要输入配对码（Telegram/WhatsApp/Signal/Discord/Slack）
- **入站消息视为不可信输入**
- **工具执行**：主 session 在宿主机执行；群聊/通道 session 可运行在沙箱模式
- **MCP 集成**：通过 `mcporter` 桥接，不直接内置 MCP runtime，可热添加/移除 MCP server

---

## 部署模型

- 本地运行：macOS / Linux / Windows (WSL2)
- Docker / Podman 支持（有 `Dockerfile`、`docker-compose.yml`、`setup-podman.sh`）
- 远程访问：Tailscale Serve/Funnel 或 SSH 隧道
- 云部署：`fly.toml` / `render.yaml` 支持 Fly.io 和 Render

---

## 设计哲学

- **本地优先**：运行在用户自己的设备上，不依赖云服务
- **安全优先**：默认安全，显式授权
- **Terminal-first**：命令行优先，确保用户理解安全态势
- **TypeScript**：保持可 hack 性，降低贡献门槛
- **核心精简**：通过插件/技能扩展，核心保持轻量
- **MCP 桥接**：通过 mcporter 而非内置，保持稳定性

---

## 项目目录结构

```
openclaw/
├── apps/                    # 客户端应用
│   ├── android/             # Android 客户端
│   ├── ios/                 # iOS 客户端
│   ├── macos/               # macOS 客户端
│   └── shared/OpenClawKit/  # 跨平台共享库
├── packages/                # npm 包
│   ├── clawdbot/
│   └── moltbot/
├── skills/                  # 技能注册中心
├── extensions/              # 扩展
├── ui/                      # WebChat 界面
├── vendor/a2ui/             # A2UI 可视化引擎
├── src/                     # 核心源码
│   ├── gateway/             # Gateway 网关（控制平面核心）
│   ├── channels/            # 消息通道抽象层
│   ├── agents/              # Agent 运行时
│   ├── routing/             # 多 Agent 路由
│   ├── providers/           # LLM Provider 抽象
│   ├── browser/             # 浏览器自动化
│   ├── canvas-host/         # Canvas 画布
│   ├── cron/                # 定时任务
│   ├── hooks/               # Webhooks
│   ├── media/               # 媒体处理
│   ├── media-understanding/ # 媒体理解
│   ├── memory/              # 记忆系统
│   ├── tts/                 # 文字转语音
│   ├── plugins/             # 插件系统
│   ├── plugin-sdk/          # 插件 SDK
│   ├── sessions/            # 会话管理
│   ├── security/            # 安全模块
│   ├── pairing/             # 配对认证
│   ├── cli/                 # CLI 入口
│   ├── commands/            # CLI 命令
│   ├── tui/                 # 终端 UI
│   ├── terminal/            # 终端工具
│   ├── whatsapp/            # WhatsApp 适配器
│   ├── telegram/            # Telegram 适配器
│   ├── discord/             # Discord 适配器
│   ├── slack/               # Slack 适配器
│   ├── signal/              # Signal 适配器
│   ├── imessage/            # iMessage 适配器
│   ├── line/                # Line 适配器
│   ├── web/                 # Web 通道
│   ├── config/              # 配置管理
│   ├── shared/              # 共享工具
│   ├── types/               # 类型定义
│   ├── utils/               # 工具函数
│   └── ...
├── docs/                    # 文档
├── test/                    # 测试
├── scripts/                 # 构建脚本
├── Dockerfile               # Docker 构建
├── docker-compose.yml       # Docker Compose
├── package.json             # 项目配置
├── pnpm-workspace.yaml      # pnpm 工作区
└── tsconfig.json            # TypeScript 配置
```
