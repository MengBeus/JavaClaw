# ZeroClaw 完整架构分析

## 概述

ZeroClaw 是 OpenClaw 的 Rust 复刻版，核心理念是 **极致轻量**。单二进制文件，<5MB 内存峰值，<10ms 冷启动，能跑在 $10 的开发板上。

## 技术栈

- 语言：Rust（100% Rust，单二进制）
- 数据库：SQLite（默认）/ PostgreSQL / Markdown 文件 / 无
- 运行时：Native / Docker / WASM
- 平台：ARM、x86、RISC-V

---

## 与 OpenClaw 对比

| | OpenClaw | ZeroClaw |
|---|---|---|
| 语言 | TypeScript/Node.js | Rust |
| 产物 | npm 包 + 多进程 | 单二进制文件 |
| 内存占用 | 较高 | ~3.9MB 峰值 |
| 扩展方式 | 插件 (npm) | Trait（编译时） |
| 架构 | WebSocket Gateway + RPC | 同样有 Gateway，但更精简 |
| 平台 | x86/ARM | x86/ARM/RISC-V |
| 运行时 | Native + Docker | Native + Docker + WASM |

---

## 核心架构：一切皆 Trait

ZeroClaw 的设计核心是 **trait-driven**，每个子系统都是一个 Rust trait，可以零代码修改替换实现：

```
trait Provider        — AI 模型后端
trait Channel         — 消息通道
trait Memory          — 持久化存储
trait Tool            — 工具能力
trait RuntimeAdapter  — 执行环境
trait SecurityPolicy  — 安全策略
trait Tunnel          — 隧道/远程访问
trait Observer        — 可观测性
```

---

## 模块拆解

### 1. Agent 核心 (`src/agent/`)

- `agent.rs` — Agent 主体
- `loop_.rs` — Agent 主循环（收消息 → 调 LLM → 执行工具 → 回复）
- `dispatcher.rs` — 消息分发
- `classifier.rs` — 意图分类
- `prompt.rs` — 提示词构建
- `memory_loader.rs` — 记忆加载

### 2. Channels 消息通道 (`src/channels/`) — 16 个适配器

- `cli.rs` — 命令行
- `telegram.rs` — Telegram
- `discord.rs` — Discord
- `slack.rs` — Slack
- `whatsapp.rs` — WhatsApp
- `signal.rs` — Signal
- `imessage.rs` — iMessage
- `matrix.rs` — Matrix
- `mattermost.rs` — Mattermost
- `email_channel.rs` — 邮件
- `irc.rs` — IRC
- `lark.rs` — 飞书
- `dingtalk.rs` — 钉钉
- `qq.rs` — QQ
- `traits.rs` — Channel trait 定义

### 3. Providers AI 后端 (`src/providers/`) — 28+ 提供商

- `openai.rs` — OpenAI
- `anthropic.rs` — Anthropic
- `gemini.rs` — Google Gemini
- `ollama.rs` — Ollama 本地模型
- `copilot.rs` — GitHub Copilot
- `openrouter.rs` — OpenRouter
- `glm.rs` — 智谱 GLM
- `openai_codex.rs` — OpenAI Codex
- `compatible.rs` — OpenAI 兼容协议（一个实现覆盖大量提供商）
- `router.rs` — 多模型路由
- `reliable.rs` — 可靠性/重试/降级

### 4. Tools 工具 (`src/tools/`) — 30 个工具

- 文件：`file_read.rs`, `file_write.rs`
- Shell：`shell.rs`
- 浏览器：`browser.rs`, `browser_open.rs`
- 搜索：`web_search_tool.rs`
- 记忆：`memory_store.rs`, `memory_recall.rs`, `memory_forget.rs`
- Cron：`cron_add.rs`, `cron_list.rs`, `cron_remove.rs`, `cron_run.rs`, `cron_runs.rs`, `cron_update.rs`
- 硬件：`hardware_board_info.rs`, `hardware_memory_map.rs`, `hardware_memory_read.rs`
- 其他：`http_request.rs`, `screenshot.rs`, `git_operations.rs`, `delegate.rs`, `pushover.rs`, `image_info.rs`, `proxy_config.rs`, `composio.rs`, `schedule.rs`
- 基础：`traits.rs`, `schema.rs`

### 5. Memory 记忆系统 (`src/memory/`) — 自研全栈搜索引擎

- `sqlite.rs` — SQLite 后端（默认）
- `postgres.rs` — PostgreSQL 后端
- `markdown.rs` — Markdown 文件后端
- `none.rs` — 无存储
- `vector.rs` — 向量存储（SQLite BLOB + 余弦相似度）
- `embeddings.rs` — 嵌入向量生成
- `chunker.rs` — 文本分块
- `lucid.rs` — 混合搜索（向量 + FTS5 关键词）
- `response_cache.rs` — 响应缓存
- `snapshot.rs` — 快照
- `hygiene.rs` — 数据清理
- `backend.rs` — 后端抽象
- `traits.rs` — Memory trait 定义

### 6. Security 安全 (`src/security/`)

- `pairing.rs` — 6 位配对码 + Bearer Token
- `policy.rs` — 安全策略
- `secrets.rs` — 密钥管理
- `audit.rs` — 审计日志
- `bubblewrap.rs` — Bubblewrap 沙箱
- `firejail.rs` — Firejail 沙箱
- `landlock.rs` — Landlock 沙箱（Linux 内核级）
- `docker.rs` — Docker 沙箱
- `detect.rs` — 自动检测可用沙箱

### 7. Runtime 运行时 (`src/runtime/`)

- `native.rs` — 原生执行
- `docker.rs` — Docker 沙箱执行
- `wasm.rs` — WASM 执行（实验性）
- `traits.rs` — RuntimeAdapter trait 定义

### 8. 其他模块

- `gateway/` — 网关（精简版，单文件 `mod.rs`）
- `tunnel/` — 远程访问隧道
- `cron/` — 定时任务引擎
- `rag/` — RAG 检索增强生成
- `skillforge/` + `skills/` — 技能系统
- `observability/` — 可观测性/监控
- `hardware/` + `peripherals/` — 硬件/嵌入式支持
- `firmware/` — 固件（项目根目录）
- `cost/` — 成本追踪
- `health/` + `heartbeat/` — 健康检查
- `approval/` — 审批流程
- `auth/` — 认证
- `config/` — 配置管理
- `daemon/` — 守护进程
- `doctor/` — 诊断工具
- `integrations/` — 第三方集成
- `onboard/` — 引导流程
- `service/` — 服务层

---

## 数据流（请求生命周期）

```
用户消息 (Telegram/Discord/CLI/...)
       │
       ▼
  Channel (trait impl) ── 消息标准化
       │
       ▼
  Gateway ── 认证、路由
       │
       ▼
  Agent Loop
       │
       ├── Classifier ── 意图分类
       ├── Memory Loader ── 加载相关记忆
       ├── Prompt Builder ── 构建提示词
       │
       ▼
  Provider (trait impl) ── 调用 LLM
       │
       ├──▶ Tool Calls ── Dispatcher 分发到具体 Tool
       │         │
       │         ▼
       │    Tool 执行 (在 Runtime 中: native/docker/wasm)
       │         │
       │         ▼
       │    结果回传 Agent Loop（可能多轮）
       │
       ▼
  Response ── 通过 Channel 回复用户
```

---

## 安全模型（比 OpenClaw 更深入）

- Gateway 默认绑定 `127.0.0.1`，拒绝 `0.0.0.0`（除非配了 tunnel）
- 4 种沙箱可选：Bubblewrap、Firejail、Landlock、Docker
- 自动检测系统可用的沙箱（`detect.rs`）
- 文件系统作用域限制：14 个系统目录 + 4 个 dotfile 被屏蔽
- Symlink 逃逸检测（通过 canonicalization）
- Channel 白名单：默认拒绝，需显式 opt-in `"*"`
- 配对认证：6 位一次性配对码 + Bearer Token

---

## 部署模型

- 本地运行：macOS / Linux / Windows (WSL2)
- Docker / Podman 支持
- 单二进制分发，`bootstrap.sh` 一键安装
- 支持嵌入式设备（ARM、RISC-V）

---

## 设计哲学

- **极致轻量**：单二进制，<5MB 内存，<10ms 冷启动
- **Trait-driven**：所有子系统通过 trait 抽象，编译时可替换
- **安全优先**：4 种沙箱、默认拒绝、symlink 检测
- **本地优先**：运行在用户自己的设备上
- **零依赖搜索**：自研向量 + 关键词混合搜索，不依赖外部搜索引擎
- **跨平台**：x86 / ARM / RISC-V，从服务器到嵌入式开发板

---

## 项目目录结构

```
zeroclaw/
├── src/                     # 核心源码
│   ├── main.rs              # 入口
│   ├── lib.rs               # 库入口
│   ├── agent/               # Agent 核心（循环、分发、分类、提示词）
│   ├── gateway/             # 网关（精简单文件）
│   ├── channels/            # 16 个消息通道适配器
│   ├── providers/           # 28+ AI 模型后端
│   ├── tools/               # 30 个工具
│   ├── memory/              # 记忆系统（SQLite/PG/向量/混合搜索）
│   ├── security/            # 安全（4种沙箱、配对、审计）
│   ├── runtime/             # 运行时（native/docker/wasm）
│   ├── rag/                 # RAG 检索增强生成
│   ├── skillforge/          # 技能锻造
│   ├── skills/              # 技能
│   ├── cron/                # 定时任务
│   ├── tunnel/              # 远程访问隧道
│   ├── hardware/            # 硬件支持
│   ├── peripherals/         # 外设
│   ├── observability/       # 可观测性
│   ├── cost/                # 成本追踪
│   ├── health/              # 健康检查
│   ├── heartbeat/           # 心跳
│   ├── approval/            # 审批流程
│   ├── auth/                # 认证
│   ├── config/              # 配置管理
│   ├── daemon/              # 守护进程
│   ├── doctor/              # 诊断工具
│   ├── integrations/        # 第三方集成
│   ├── onboard/             # 引导流程
│   ├── service/             # 服务层
│   ├── migration.rs         # 数据库迁移
│   ├── identity.rs          # 身份
│   └── util.rs              # 工具函数
├── crates/robot-kit/        # 独立 crate
├── benches/                 # 性能基准测试
├── tests/                   # 集成测试
├── test_helpers/            # 测试工具
├── fuzz/                    # 模糊测试
├── firmware/                # 固件
├── python/                  # Python 绑定
├── docs/                    # 文档
├── examples/                # 示例
├── scripts/                 # 脚本
├── dev/                     # 开发工具
├── Cargo.toml               # Rust 依赖
├── Dockerfile               # Docker 构建
├── docker-compose.yml       # Docker Compose
├── bootstrap.sh             # 一键安装
└── rust-toolchain.toml      # Rust 版本
```
