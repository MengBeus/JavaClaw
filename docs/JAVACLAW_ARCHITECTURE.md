# JAVAClaw 完整架构设计

> 基于 OpenClaw (TypeScript) 和 ZeroClaw (Rust) 的架构分析，为 Java 生态设计的 AI 助手基础设施。

---

## 1. 定位

JAVAClaw 是一个 **消息驱动的 AI Agent 编排器**，运行在用户自己的设备上，通过多平台消息通道与用户交互。

核心理念：**稳而强** — 企业级基础设施、运行时可扩展、Java 生态成熟方案优先。

---

## 2. 技术选型

- Java 21（Virtual Threads 处理并发）
- Spring Boot 3（保持轻量，不滥用）
- PostgreSQL（会话/审计持久化）
- Lucene（记忆混合检索）
- 单 Maven 模块 + 包分层（扁平结构，参考 zeroclaw/openclaw）
- WebSocket + HTTP（对外协议）

---

## 3. 整体架构

6 个核心模块 + 3 个支撑模块：

```
┌─────────────────────────────────────────────────┐
│                   Gateway                        │
│           （认证、路由、事件广播）                  │
└──────────┬──────────────────────┬────────────────┘
           │                      │
     ┌─────▼─────┐         ┌─────▼─────┐
     │  Channel   │         │  Client   │
     │ Adapters   │         │ (CLI/Web) │
     └─────┬─────┘         └─────┬─────┘
           │                      │
           └──────────┬───────────┘
                      │
                ┌─────▼─────┐
                │   Agent    │
                │   Loop     │
                └──┬─────┬──┘
                   │     │
            ┌──────▼┐  ┌─▼───────┐
            │Provider│  │  Tools  │
            │ (LLM)  │  │         │
            └────────┘  └─────────┘
                   │
            ┌──────▼────┐
            │  Memory    │
            │ (Lucene)   │
            └────────────┘

支撑模块：Security | Config | Observability
```

---

## 4. 核心模块详解

### 4.1 Gateway（网关）

系统唯一入口，不做业务逻辑，只做流量调度。

**职责**：
- 认证：Token / 配对码验证
- 路由：将请求分发到对应的 Agent 会话
- 事件广播：WebSocket 推送状态变更
- 限流：入口级流量控制

**对外协议**：
- `WS /ws` — 长连接，流式响应
- `POST /v1/chat` — HTTP 同步/SSE 流式
- `GET /health` — 健康检查

**设计参考**：
- 取 OpenClaw 的"Gateway 即控制平面"思路
- 但不像 OpenClaw 那样把所有逻辑塞进 Gateway，保持薄层

### 4.2 Channel（消息通道）

每个聊天平台一个适配器，职责单一：双向翻译消息格式。

**统一接口**：

```java
public interface ChannelAdapter {
    String id();
    void start(MessageSink sink);    // 启动监听，收到消息丢给 sink
    void send(OutboundMessage msg);  // 发送回复到平台
    void stop();
}
```

**MVP 阶段适配器**：
- Telegram（优先，API 最友好）
- Discord
- CLI（本地调试用）

**后续扩展**：
- Slack、微信、飞书、钉钉、QQ、邮件

**设计参考**：
- 学 ZeroClaw 的扁平结构：每个适配器一个类，不过度抽象
- 学 OpenClaw 的白名单机制：默认拒绝未知发送者

### 4.3 Agent（AI 大脑）

系统核心，负责"思考"。运行一个循环直到 LLM 返回最终文本。

**主循环**：

```
收到消息
  → 加载会话历史
  → 检索相关记忆（Memory）
  → 构建提示词（系统提示 + 记忆 + 历史 + 用户消息）
  → 调用 LLM（Provider）
  → LLM 返回 tool_call？
      是 → 执行工具 → 结果回传 → 再调 LLM（循环）
      否 → 拿到最终回复 → 保存记忆 → 返回
```

**关键接口**：

```java
public interface AgentOrchestrator {
    AgentResponse run(AgentRequest request);
    Flux<AgentEvent> runStream(AgentRequest request);  // 流式（仅对外边界）
}
```

**并发模型**：内部同步 + 边界流式
- 内部一律同步阻塞，跑在 Virtual Threads 上（Tool.execute、Provider.chat、MemoryStore.recall）
- Flux 只出现在两个边界：`runStream()` 对外推送事件、`chatStream()` 接收 LLM 流式 token
- Provider 内部用 `BlockingQueue` 把流式 token 转为同步 `Iterator<ChatEvent>`，Agent Loop 同步消费
- 取消语义：Virtual Thread 用 `Thread.interrupt()`，边界处转换为 `Disposable.dispose()`

```
客户端 ←[Flux]← Gateway ←[同步/VT]← Agent Loop ←[Iterator]← Provider ←[Flux]← LLM API
         边界1                                                  边界2
```

**设计参考**：
- 学 ZeroClaw 的 Agent Loop 内嵌模式（不像 OpenClaw 那样拆成独立 RPC 进程）
- 学 ZeroClaw 的意图分类器：简单消息（如"你好"）不需要调 LLM

### 4.4 Provider（模型后端）

对接各种 LLM，核心设计：一个 OpenAI 兼容基类覆盖 90% 的模型商。

**关键接口**：

```java
public interface ModelProvider {
    String id();
    ChatResponse chat(ChatRequest request);
    Flux<ChatEvent> chatStream(ChatRequest request);
}
```

**实现层次**：

```
ModelProvider (接口)
  ├── OpenAiCompatibleProvider (基类，覆盖所有兼容 OpenAI 协议的)
  │     ├── OpenAiProvider
  │     ├── DeepSeekProvider
  │     ├── MoonshotProvider
  │     ├── ... (只需改 baseUrl 和认证)
  ├── AnthropicProvider (独立协议)
  ├── GeminiProvider (独立协议)
  ├── OllamaProvider (本地模型)
  └── ProviderRouter (多模型路由 + 降级)
```

**可靠性层**（参考 ZeroClaw 的 `reliable.rs`，补全工程化要素）：
- 超时预算：每个请求有总超时（如 120s），重试共享预算，不是每次重试都给满时间
- 重试：指数退避，在超时预算内重试，预算耗尽直接降级
- 熔断：连续失败 ≥ 3 次 → 熔断打开 → 直接走降级模型；每 60s 放一个探测请求，成功则关闭
- 降级链：有序降级列表（如 deepseek-v3 → gpt-4o-mini → ollama/qwen2.5），不只是"主+备"
- 限流联动：记录每个模型的 RPM/TPM 消耗，接近限额时主动切换，不等 429
- 幂等键：非流式请求生成 `idempotency_key = hash(model + messages)`，命中缓存直接返回，避免重试重复计费
- 成本追踪：记录每次调用的 token 消耗和费用

**可靠性配置示例**：

```yaml
providers:
  primary: deepseek-v3
  fallback:
    - gpt-4o-mini
    - ollama/qwen2.5   # 本地模型，永远可用
  timeout_budget: 120s
  circuit_breaker:
    threshold: 3
    reset_interval: 60s
```

### 4.5 Tool（工具）

Agent 可调用的能力。统一接口，注册中心管理。

**关键接口**：

```java
public interface Tool {
    String name();
    String description();
    JsonSchema inputSchema();
    ToolResult execute(ToolContext ctx, JsonNode input);
}
```

**MVP 工具集**：
- `ShellTool` — 执行系统命令
- `FileReadTool` / `FileWriteTool` — 文件读写
- `HttpRequestTool` — HTTP 请求
- `WebSearchTool` — 网页搜索
- `MemoryStoreTool` / `MemoryRecallTool` — 记忆存取

**后续扩展**：
- `BrowserTool` — 浏览器自动化
- `GitTool` — Git 操作
- `CronTool` — 定时任务
- `ScreenshotTool` — 截图

**安全**：
- 危险工具（Shell、FileWrite）需要审批确认
- 所有工具通过 `ToolExecutor` 接口执行，不直接调用系统 API
- `ToolExecutor` 两个实现：`DockerExecutor`（Docker 可用时）和 `RestrictedNativeExecutor`（降级方案：工作目录白名单、命令黑名单、执行超时）
- 工具执行与沙箱同步上线（Phase 3），不存在"裸跑"窗口期

### 4.6 Memory（记忆）

不只是"存聊天记录"，而是一个 **混合检索引擎**。Java 用 Lucene 实现，比 ZeroClaw 的 SQLite FTS5 方案更强。

**两层存储**：

```
会话历史（PostgreSQL） ← source of truth
  → 完整的消息记录，按 session 分组

长期记忆（Lucene） ← 派生索引，可从 PG 重建
  → 向量索引：语义相似度检索
  → 关键词索引：BM25 精确匹配
  → 混合排序：Reciprocal Rank Fusion (RRF)
```

**一致性模型**：最终一致，PostgreSQL 是 source of truth
- 写入顺序：先写 PG（事务提交）→ 再异步更新 Lucene 索引
- 失败补偿：`index_queue` 表记录待索引条目，后台定时重试
- 全量重建：`/admin/reindex` 命令，启动时检测索引版本号，不匹配则自动重建

**关键接口**：

```java
public interface MemoryStore {
    void store(String content, Map<String, Object> metadata);
    List<MemoryResult> recall(String query, int topK);
    void forget(String memoryId);
}
```

**检索流程**：
1. 用户消息进来
2. 向量搜索：将消息转为 embedding，找语义相似的记忆，返回 Top-N 带排名
3. 关键词搜索：BM25 匹配关键词，返回 Top-N 带排名
4. RRF 融合：`RRF_score(d) = 1/(k + rank_vector) + 1/(k + rank_keyword)`，k=60
5. 按 RRF 分数重排，取 Top-K 注入 Agent 的提示词上下文

> 不用 `α*vector + (1-α)*keyword` 线性加权——向量分数和 BM25 分数量纲、分布不一致，线性加权召回质量不稳定。RRF 只依赖排名，天然解决这个问题。

---

## 5. 支撑模块

### 5.1 Security（安全）

- **配对认证**：首次连接生成 6 位配对码，验证后颁发 Token
- **工具审批**：危险操作弹出确认，用户同意后才执行
- **沙箱执行**：Docker 容器隔离工具执行环境
- **审计日志**：所有关键操作写入日志
- **白名单**：Channel 默认拒绝未知发送者

### 5.2 Config（配置）

- `~/.javaclaw/config.yaml` — 主配置文件
- 环境变量覆盖（12-Factor 风格）
- 配置分三级：
  - **静态**（改了要重启）：数据库连接、端口、沙箱类型、MCP Server 配置
  - **热更新**（运行时可改）：模型选择、Channel 启用/禁用、工具权限、提示词模板
  - **安全策略**（热更新但需确认）：白名单、审批规则、限流阈值——变更需配对码二次验证
- 热更新机制：`ConfigWatcher` 监听配置文件变更（`WatchService`），解析后通知实现了 `Reconfigurable` 接口的组件
- 不依赖 `@RefreshScope`，避免 Spring 魔法带来的一致性问题

### 5.3 Observability（可观测性）

- **指标**：Micrometer → Prometheus（LLM 调用延迟、token 消耗、工具执行次数）
- **追踪**：OpenTelemetry（请求全链路追踪）
- **成本**：每次 LLM 调用记录 token 数 × 单价，按日/月汇总
- **诊断**：`/doctor` 命令自检环境、连接、配置状态

---

## 6. 数据流

```
用户 (Telegram/Discord/CLI/...)
       │
       ▼
  Channel Adapter ── 统一消息格式
       │
       ▼
  Gateway ── 认证 → 路由到 Session
       │
       ▼
  Agent Loop
       │
       ├── 1. 加载会话历史 (PostgreSQL)
       ├── 2. 检索相关记忆 (Lucene 混合搜索)
       ├── 3. 构建提示词
       ├── 4. 调用 Provider (LLM)
       │        │
       │        ├── 返回 tool_call？
       │        │     是 → 执行 Tool → 结果回传 → 回到步骤 4（多轮）
       │        │     否 → 拿到最终回复
       │        │
       ├── 5. 保存记忆
       ├── 6. 记录成本
       │
       ▼
  Channel Adapter ── 翻译回平台格式 → 发送
```

---

## 7. 项目结构（单 Maven 模块 + 包分层）

```
javaclaw/
├── pom.xml
├── docker-compose.yml
├── config/config-example.yaml
└── src/main/
    ├── java/com/javaclaw/
    │   ├── JavaClawApp.java          # @SpringBootApplication 启动入口
    │   ├── shared/                   # 公共模型 + 配置
    │   │   ├── model/                # InboundMessage, OutboundMessage, AgentRequest, AgentResponse, Session, ChatMessage
    │   │   └── config/               # JavaClawConfig, ConfigLoader
    │   ├── gateway/                  # HTTP + WebSocket 端点
    │   ├── agent/                    # Agent 核心循环
    │   ├── providers/                # LLM 模型后端
    │   ├── channels/                 # CLI, Telegram, Discord, Slack...
    │   ├── tools/                    # 工具框架 + 具体工具
    │   ├── sessions/                 # 会话持久化
    │   ├── approval/                 # 审批策略
    │   ├── auth/                     # 配对认证 + 白名单
    │   ├── security/                 # 沙箱执行器
    │   ├── memory/                   # Lucene 混合检索
    │   ├── observability/            # 成本追踪 + 指标 + 自检
    │   ├── mcp/                      # MCP 协议客户端
    │   └── skills/                    # Skill 加载与注册
    └── resources/
        ├── application.yml
        └── db/migration/
```

---

## 8. 扩展系统（MCP + Skill）

### 8.1 MCP（外部工具扩展）

通过 MCP（Model Context Protocol）协议扩展工具能力，不自建插件系统。

**MCP Client**：
- 管理外部 MCP Server 子进程的生命周期（启动、停止、健康检查）
- 通过 stdin/stdout JSON-RPC 2.0 通信
- `~/.javaclaw/config.yaml` 的 `mcp-servers` 节声明要加载的 server
- 自动将 MCP Server 暴露的 tools 注册到 ToolRegistry

**配置示例**：
```yaml
mcp-servers:
  filesystem:
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/docs"]
  github:
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: "xxx"
```

**优势**：
- 语言无关 — MCP Server 可用任何语言编写
- 天然进程隔离 — 崩溃不影响主程序
- 生态现成 — 直接复用社区已有的 MCP Server
- 标准协议 — 与 Claude Code、Cursor 等工具生态互通

### 8.2 Skill（行为模式扩展）

Skill 是对「system prompt + 工具子集」的打包，定义 Agent 在特定场景下的行为模式。

**目录结构**：`~/.javaclaw/skills/<name>.yaml`

**Skill 定义格式**：
```yaml
name: code-review
trigger: /review
description: 审查指定文件的代码质量
system_prompt: |
  你是代码审查专家。阅读用户指定的文件，
  从安全性、可读性、性能三个维度给出改进建议。
tools: [file_read, shell]
```

**工作机制**：
- `SkillLoader` 启动时扫描 `~/.javaclaw/skills/*.yaml`，注册到 SkillRegistry
- 用户输入 `/review src/Main.java` 时，匹配 trigger，替换 system prompt 和工具子集
- 未匹配任何 skill 时走默认 PromptBuilder 逻辑

**与 MCP 的关系**：MCP 扩展「Agent 能做什么」（工具能力），Skill 扩展「Agent 怎么做」（行为模式）。两者互补。

---

## 9. MVP 路线

### Phase 1：骨架

- 单 Maven 模块 + 包分层搭建
- `shared` 包：统一消息模型 + 配置
- `gateway` 包：启动入口 + WebSocket
- 配置加载、日志、Docker Compose（PostgreSQL）

### Phase 2：Agent 闭环

- `providers` 包：OpenAiCompatibleProvider + 一个具体实现
- `agent` 包：AgentLoop 主循环（无工具调用）
- `channels` 包：CliAdapter（本地调试）
- 目标：CLI 输入 → Agent 调 LLM → CLI 输出

### Phase 3：工具 + 会话 + 基础沙箱

- `tools` 包：ShellTool + FileReadTool
- `security` 包：ToolExecutor 接口 + DockerExecutor + RestrictedNativeExecutor（基础沙箱，与工具同步上线）
- `agent` 包：支持 tool_call 多轮循环
- `sessions` 包：会话历史持久化（PostgreSQL）
- `approval` 包：工具审批流程
- 目标：Agent 能调用工具（有沙箱保护），会话可持续

### Phase 4：消息平台

- `channels` 包：TelegramAdapter + DiscordAdapter
- `auth` 包：配对认证 + 白名单
- 目标：从 Telegram/Discord 收发消息，完成真实闭环

### Phase 5：记忆 + 可观测

- `memory` 包：Lucene 混合检索
- `observability` 包：成本追踪 + 指标 + /doctor
- Agent 集成记忆检索
- 目标：Agent 有长期记忆，运行可监控

### Phase 6：MCP + Skill + 沙箱加固

- `mcp` 包：MCP Client（JSON-RPC 2.0 over stdio），自动发现并注册外部工具
- `skills` 包：SkillLoader + SkillRegistry，YAML 定义行为模式，斜杠命令触发
- 沙箱加固：资源限制（CPU/内存/网络）、自动检测 Docker 可用性、策略配置
- 更多内置工具（HttpRequest、WebSearch、Git、Browser）
- 更多 Channel（Slack）；微信、飞书为后续可选
- 目标：系统可扩展，安全加固完成

---

## 10. MVP 验收标准

1. 从 Telegram 收消息 → Agent 调 LLM → 回复（闭环）
2. Agent 能调用至少一个工具（含审批）
3. 会话可持续，重启后历史可恢复
4. 有配对认证 + 白名单
5. 有成本追踪 + 健康检查
