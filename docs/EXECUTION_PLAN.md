# JAVAClaw 完整执行计划（Phase 1-6）

## Context

JAVAClaw 的架构设计已完成。现在进入编码阶段。
项目采用**单 Maven 模块 + 包分层**架构（参考 zeroclaw/openclaw 的扁平模块结构），直接在 `F:\Programs\project 2\` 根目录构建。

---

## 前置准备

1. **整理根目录**
   - 架构文档移入 `docs/` 目录
   - `_openclaw/`、`_zeroclaw/` 加入 `.gitignore`（参考代码，不提交）
   - 创建 `.gitignore`（Java + Maven + IDE 通用规则）

2. **环境确认**
   - JDK 21+、Maven 3.9+、Docker Desktop（PostgreSQL 用）

---

## Phase 1：骨架搭建

### Step 1：项目结构

单 `pom.xml`，包分层：

```
F:\Programs\project 2\
├── pom.xml
├── docker-compose.yml
├── config/config-example.yaml
└── src/main/
    ├── java/com/javaclaw/
    │   ├── JavaClawApp.java          # @SpringBootApplication 启动入口
    │   ├── shared/                   # 公共模型 + 接口
    │   │   ├── model/                # InboundMessage, OutboundMessage, AgentRequest, AgentResponse, Session, ChatMessage
    │   │   └── config/               # JavaClawConfig, ConfigLoader
    │   └── gateway/                  # HTTP + WebSocket 端点
    │       ├── WebSocketConfig.java
    │       ├── WebSocketHandler.java
    │       ├── ChatController.java
    │       └── HealthController.java
    └── resources/
        ├── application.yml
        └── db/migration/
            └── V1__init_schema.sql   # 空占位
```

**POM 关键配置**：
- `groupId`: `com.javaclaw`，`artifactId`: `javaclaw`
- `java.version`: 21
- `spring-boot-starter-web` + `spring-boot-starter-websocket`
- `flyway-core` + `flyway-database-postgresql` + `postgresql`
- `jackson-databind` + `snakeyaml`

### Step 2：shared（公共模型）

**消息模型**（Java record）：
- `InboundMessage(senderId, channelId, content, timestamp)`
- `OutboundMessage(channelId, content, metadata)`
- `AgentRequest(sessionId, message, context)`
- `AgentResponse(content, toolCalls, usage)`

**会话模型**：
- `Session(id, userId, channelId, createdAt)`
- `ChatMessage(role, content, toolCallId)`

**配置**：
- `JavaClawConfig` — 映射 `config.yaml`
- `ConfigLoader` — 加载 `~/.javaclaw/config.yaml` + 环境变量覆盖

### Step 3：gateway（启动入口）

- `JavaClawApp.java` — `@SpringBootApplication`
- `HealthController` — `GET /health` 返回 OK
- `ChatController` — `POST /v1/chat`（Phase 1 返回 echo）
- `WebSocketHandler` — WS echo
- `application.yml` — 端口 18789，WS 路径 `/ws`

### Step 4：docker-compose.yml + 配置模板

- PostgreSQL 16，端口 5432，数据库 javaclaw
- `config/config-example.yaml`

### Step 5：Flyway 数据库迁移

- `application.yml` 配置 Flyway 自动迁移
- `V1__init_schema.sql` 空占位（Phase 3 填充）

### Step 6：验证

1. `mvn clean compile` 通过
2. `docker-compose up -d` PostgreSQL 启动
3. `http://localhost:18789/health` 返回 OK
4. WebSocket echo 正常
- 单测：shared 模型类序列化/反序列化
- 集成脚本：`mvn compile` + 启动 + curl health + ws echo

---

## Phase 2：Agent 闭环

> 目标：CLI 输入 → Agent 调 LLM → CLI 输出

### Step 1：providers（模型后端）

```
com.javaclaw.providers/
├── ModelProvider.java              # 接口（chat, chatStream, id）
├── ChatRequest.java / ChatResponse.java / ChatEvent.java
├── OpenAiCompatibleProvider.java   # 抽象基类，覆盖所有兼容 OpenAI 协议的模型商
├── DeepSeekProvider.java           # 第一个具体实现
├── ProviderRouter.java             # 多模型路由（先单模型直通）
└── ResilientCall.java              # 最小可靠性：超时 30s + 重试 2 次（指数退避）
```

### Step 2：agent（Agent 核心）

```
com.javaclaw.agent/
├── AgentOrchestrator.java          # 接口
├── DefaultAgentOrchestrator.java   # 实现
├── AgentLoop.java                  # 主循环（调 LLM → 返回）
├── PromptBuilder.java              # 构建提示词
└── Classifier.java                 # 意图分类器（留桩）
```

### Step 3：channels（CLI 适配器）

```
com.javaclaw.channels/
├── ChannelAdapter.java             # 接口（id, start, send, stop）
├── ChannelRegistry.java            # 注册中心
└── CliAdapter.java                 # stdin/stdout，/quit 退出
```

### Step 4：Gateway 串联

修改 `JavaClawApp`，串联 Channel → Agent → Provider。

### 验证

CLI 输入 → 调 DeepSeek → 返回回复，多轮对话。
- 单测：ResilientCall 重试逻辑（mock HTTP）、PromptBuilder 拼装
- 集成脚本：启动 CLI → 发消息 → 验证收到 LLM 回复

---

## Phase 3：工具 + 会话 + 沙箱

> 目标：Agent 能调用工具（有沙箱保护），会话可持续

### Step 1：tools（工具框架 + 首批工具）

```
com.javaclaw.tools/
├── Tool.java                       # 接口（name, description, inputSchema, execute）
├── ToolRegistry.java               # 注册中心，按 name 查找
├── ToolContext.java                 # 执行上下文（workDir, sessionId, 权限）
├── ToolResult.java                  # 执行结果（output, isError）
├── ShellTool.java                  # 执行系统命令，通过 ToolExecutor
├── FileReadTool.java               # 读取文件
└── FileWriteTool.java              # 写入文件
```

**沙箱层**：
```
com.javaclaw.security/
├── ToolExecutor.java               # 接口
├── DockerExecutor.java             # Docker 沙箱执行
└── RestrictedNativeExecutor.java   # 降级：白名单 + 黑名单 + 超时
```

### Step 2：Agent 支持 tool_call 多轮循环

修改 `AgentLoop.java`，加入 tool_call 循环（最大 10 轮）。

### Step 3：会话持久化

```
com.javaclaw.sessions/
├── SessionStore.java               # 接口（load, save, delete）
└── PostgresSessionStore.java       # PostgreSQL 实现
```

Flyway 迁移 `V2__sessions.sql`：sessions + chat_messages 表。

### Step 4：approval（工具审批）

```
com.javaclaw.approval/
├── ApprovalStrategy.java           # 审批策略接口（通道无关）
├── CliApprovalStrategy.java        # CLI stdin y/n
├── DangerousOperation.java         # 注解
└── ApprovalInterceptor.java        # 拦截器，按通道选择策略
```

### Step 5：沙箱三级策略

1. **Docker 可用** → 直接沙箱执行，不问用户
2. **Docker 不可用 + 配置了 `sandbox.allow-native-fallback: true`** → 走正常审批
3. **Docker 不可用 + 没配置** → ApprovalStrategy 动态请求，额外警告"[无沙箱保护]"

- FileReadTool 不受限（只读）
- 启动时打印：`[WARN] Docker unavailable, dangerous tools will require explicit approval`

### 验证

- 单测：ApprovalInterceptor 拦截逻辑、沙箱三级策略选择
- 集成脚本：启动 → 调工具 → 审批 → 执行 → 验证结果

---

## Phase 4：消息平台

> 目标：从 Telegram/Discord 收发消息

### Step 1：TelegramAdapter

```
com.javaclaw.channels/
└── TelegramAdapter.java            # Long Polling，telegrambots-longpolling SDK
```

- Bot Token 从配置读取
- 消息超 4096 字符自动分段
- Phase 4 只处理文本消息

### Step 2：DiscordAdapter

```
com.javaclaw.channels/
└── DiscordAdapter.java             # JDA 库，监听 @提及 和 DM
```

- 消息超 2000 字符自动分段

### Step 3：auth（配对认证 + 白名单）

```
com.javaclaw.auth/
├── PairingService.java             # 6 位配对码，一次性
├── TokenService.java               # Token 生成/验证
└── WhitelistService.java           # 已认证用户白名单，持久化 PostgreSQL
```

Flyway 迁移 `V3__whitelist.sql`。

### Step 4：通道内审批策略

```
com.javaclaw.approval/
├── TelegramApprovalStrategy.java   # Inline Keyboard（✅/❌），超时 60s 自动拒绝
└── DiscordApprovalStrategy.java    # Button Component 审批
```

### 验证

- 单测：PairingService 配对码生成/验证、WhitelistService 持久化
- 集成脚本：模拟 Telegram 消息 → 配对 → 对话 → 工具审批

### 备注（来自 Phase 2 遗留）

- **`/quit` 生命周期改造**：Phase 2 中 CLI `/quit` 会调 `ctx.close()` 关闭整个程序（因为只有 CLI 一个通道）。Phase 4 多通道共存时需改为：`/quit` 只停 CLI 自身，由 ChannelRegistry 判断是否所有通道都已停止，全停才关闭 Spring 上下文。参见 ISSUES.md 问题 20。

---

## Phase 5：记忆 + 可观测

> 目标：Agent 有长期记忆，运行可监控

### Step 1：memory（Lucene 混合检索）

```
com.javaclaw.memory/
├── MemoryStore.java                # 接口（store, recall, forget）
├── LuceneMemoryStore.java          # Lucene 实现
├── EmbeddingService.java           # 调 LLM embedding API
└── HybridSearcher.java             # 向量 + BM25 + RRF 融合
```

- Lucene 索引目录：`~/.javaclaw/index/`
- 中文分词：`SmartChineseAnalyzer`
- PostgreSQL 是 source of truth，Lucene 异步同步

### Step 2：observability（可观测性）

```
com.javaclaw.observability/
├── CostTracker.java                # token × 单价，按日/月汇总
├── MetricsConfig.java              # Micrometer 内存指标
└── DoctorCommand.java              # /doctor 自检
```

Flyway 迁移 `V4__llm_usage.sql`。

### Step 3：Agent 集成记忆

```
com.javaclaw.tools/
├── MemoryStoreTool.java            # Agent 主动存记忆
└── MemoryRecallTool.java           # Agent 主动搜记忆
```

修改 `AgentLoop`：消息进来先 recall，对话结束后 store。

### 验证

- 单测：HybridSearcher RRF 融合排序、CostTracker 计费逻辑
- 集成脚本：存记忆 → 新会话检索 → 验证召回、`/doctor` 全部通过

---

## Phase 6：MCP + Skill + 沙箱加固

> 目标：系统可扩展，安全加固完成

### Step 1：MCP Client

```
com.javaclaw.mcp/
├── McpClient.java                 # JSON-RPC 2.0 over stdio，管理单个 MCP Server
├── McpManager.java                # 读取 config.yaml mcp-servers 节，批量启动/停止
└── McpToolBridge.java             # 将 MCP Server 暴露的 tools 适配为 Tool 接口注册到 ToolRegistry
```

- 从 `~/.javaclaw/config.yaml` 的 `mcp-servers` 节读取 server 配置
- 每个 server 起子进程，通过 stdin/stdout JSON-RPC 2.0 通信
- 启动时调用 `tools/list` 获取工具列表，自动注册到 ToolRegistry
- 工具调用时通过 `tools/call` 转发，结果回传给 Agent

### Step 2：Skill 系统

```
com.javaclaw.skills/
├── SkillLoader.java               # 扫描 ~/.javaclaw/skills/*.yaml，解析 Skill 定义
├── SkillRegistry.java             # 按 trigger 注册，匹配斜杠命令
└── Skill.java                     # Skill 数据模型（name, trigger, system_prompt, tools）
```

- 启动时扫描 `~/.javaclaw/skills/*.yaml`，解析并注册
- 消息路由时检测 `/` 前缀，匹配 trigger 后替换 system prompt 和工具子集
- 未匹配时走默认 PromptBuilder 逻辑

### Step 3：沙箱加固

- Docker 执行加 `--memory=256m --cpus=0.5 --pids-limit=64`
- 默认 `--network=none`，需要网络的工具单独放行
- `config.yaml` 配置沙箱策略（超时、内存、网络白名单）

### Step 4：更多工具和 Channel

```
com.javaclaw.tools/
├── HttpRequestTool.java
├── WebSearchTool.java
├── GitTool.java
└── BrowserTool.java                # Playwright，可选

com.javaclaw.channels/
└── SlackAdapter.java
```

### Step 5：Provider 可靠性增强（在 Phase 2 最小版基础上）

```
com.javaclaw.providers/
├── ReliableProvider.java           # 装饰器
├── CircuitBreaker.java             # 熔断（连续失败≥3 → 打开 → 60s 探测）
└── TimeoutBudget.java              # 超时预算（总 120s）
```

降级链：primary → fallback 列表自动切换。

### 验证

- 单测：McpClient JSON-RPC 通信、SkillLoader YAML 解析、CircuitBreaker 状态机
- 集成脚本：启动 mock MCP Server → 验证工具自动注册 → 斜杠命令触发 Skill → mock 主模型故障 → 验证自动降级

---

## 完整包结构总览

```
src/main/java/com/javaclaw/
├── JavaClawApp.java
├── shared/          # 公共模型 + 配置
│   ├── model/
│   └── config/
├── gateway/         # HTTP + WebSocket 端点
├── agent/           # Agent 核心循环
├── providers/       # LLM 模型后端
├── channels/        # CLI, Telegram, Discord, Slack...
├── tools/           # 工具框架 + 具体工具
├── sessions/        # 会话持久化
├── approval/        # 审批策略
├── auth/            # 配对认证 + 白名单
├── security/        # 沙箱执行器
├── memory/          # Lucene 混合检索
├── observability/   # 成本追踪 + 指标 + 自检
└── plugins/         # 插件加载
```

## 执行顺序总览

| Phase | 目标 | 核心包 | 验收标准 |
|-------|------|--------|---------|
| 1 | 骨架 | shared, gateway | `mvn compile` 通过，health 端点可访问 |
| 2 | Agent 闭环 | providers, agent, channels | CLI → LLM → 回复 |
| 3 | 工具+会话 | tools, security, sessions, approval | 工具调用（有审批），会话持久化 |
| 4 | 消息平台 | channels, auth, approval | Telegram/Discord 收发，配对认证 |
| 5 | 记忆+可观测 | memory, observability | 跨会话记忆，成本追踪 |
| 6 | 插件+加固 | plugins, providers, security | 插件加载，沙箱加固，降级链 |

## 全局注意事项

1. 每个 Phase 完成后 git commit
2. 先跑通再优化
3. 接口先行
4. 配置外置（不硬编码 API Key）
5. 日志规范（SLF4J）
6. 错误处理不能静默吞掉
7. **测试闸门**：每个 Phase 至少 1 个单测 + 1 个集成验收脚本，`mvn test` 通过才能 commit
8. **审批通道感知**：ApprovalStrategy 接口抽象，各 Channel 提供自己的实现
9. **沙箱三级策略**：无 Docker 时危险工具通过 ApprovalStrategy 动态请求用户授权
