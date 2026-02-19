# JAVAClaw 架构决策记录：每个模块为什么这样选

> 逐模块对比 OpenClaw 和 ZeroClaw 的做法，说明 JAVAClaw 的选择理由。

---

## 1. Gateway（网关）

**OpenClaw 的做法**：重量级控制平面
- 160+ 个文件，是整个系统最大的模块
- 所有逻辑都经过 Gateway：认证、会话管理、Agent 提示词构建、配置热重载、OpenAI 兼容 HTTP 端点
- Gateway 就是"大脑"，Agent 只是它调用的一个 RPC 服务

**ZeroClaw 的做法**：极简薄层
- 单文件 `mod.rs`
- 只做认证和转发，真正的逻辑在 Agent Loop 里
- Gateway 只是"门卫"，不是"大脑"

**JAVAClaw 选择**：偏 ZeroClaw 的薄层方案

**理由**：
- OpenClaw 的 Gateway 太重了，职责过多，改一个功能要动 Gateway
- ZeroClaw 的思路更清晰：Gateway 只管"谁能进来、往哪走"，业务逻辑交给 Agent
- 但比 ZeroClaw 稍厚一点：加上事件广播和限流，因为 Java 的 Spring WebSocket 天然支持这些
- 这样 Gateway 只有 4 个职责：认证、路由、事件广播、限流

---

## 2. Channel（消息通道）

**OpenClaw 的做法**：重抽象、多层级
- 每个平台一个独立目录（`src/whatsapp/`、`src/telegram/` 等）
- 有 `registry.ts`（注册中心）+ `session.ts`（会话管理）+ `command-gating.ts`（命令门控）+ `mention-gating.ts`（@提及门控）
- 有独立的 `allowlists/` 白名单子目录和 `plugins/` 通道级插件
- 抽象层较厚，灵活但复杂

**ZeroClaw 的做法**：扁平结构
- 所有适配器平铺在 `src/channels/` 下，每个平台一个 `.rs` 文件
- 一个 `traits.rs` 定义 Channel trait，所有适配器实现这个 trait
- 没有通道级插件，没有多层抽象
- 简单直接，一眼看懂

**JAVAClaw 选择**：偏 ZeroClaw 的扁平结构 + OpenClaw 的白名单机制

**理由**：
- ZeroClaw 的扁平结构更清晰：一个接口 `ChannelAdapter`，每个平台一个实现类，不需要中间层
- OpenClaw 的通道级插件和命令门控过度设计了——消息过滤应该在 Gateway 层统一处理，不该每个 Channel 自己搞
- 但 OpenClaw 的白名单机制很实用：默认拒绝未知发送者，这是安全底线，必须要有
- Java 的做法：`ChannelAdapter` 接口 + 每平台一个类 + `ChannelRegistry` 管理注册，白名单逻辑放在 Security 模块统一处理

---

## 3. Agent（AI 大脑）

**OpenClaw 的做法**：RPC 独立进程
- Agent 叫 "Pi Agent Runtime"，以 RPC 模式运行在独立进程中
- Gateway 负责编排：构建提示词（`agent-prompt.ts` 在 Gateway 里）、管理会话、调度 Agent
- 支持多 Agent 路由到隔离工作区
- Agent 本身比较"被动"，是 Gateway 的执行者

**ZeroClaw 的做法**：内嵌主循环
- Agent Loop 直接在主进程内运行
- Agent 自己管理整个生命周期：加载记忆、构建提示词（`prompt.rs` 在 Agent 里）、调 LLM、执行工具
- 有专用的 `classifier.rs` 意图分类器：简单消息不调 LLM
- Agent 是"主角"，不是被调用的服务

**JAVAClaw 选择**：偏 ZeroClaw 的内嵌循环模式

**理由**：
- OpenClaw 把 Agent 拆成独立 RPC 进程，增加了通信开销和部署复杂度，对单用户本地应用来说没必要
- ZeroClaw 的 Agent 自治更合理：提示词构建、记忆加载这些逻辑本来就属于 Agent，不该放在 Gateway
- ZeroClaw 的意图分类器很聪明：用户说"你好"不需要调 LLM，省钱省时间，JAVAClaw 也要有
- 但保留 OpenClaw 的多 Agent 路由思路作为未来扩展点——通过 `AgentOrchestrator` 接口，未来可以支持多 Agent
- Java 用 Virtual Threads 处理 Agent Loop 的并发，比 OpenClaw 的多进程 RPC 更轻量

---

## 4. Provider（模型后端）

**OpenClaw 的做法**：Provider 目录，具体实现不透明
- `src/providers/` 目录下有多个 Provider
- 没有明确的"兼容层"设计，每个 Provider 似乎独立实现
- 没有明确的可靠性层（重试、降级）

**ZeroClaw 的做法**：`compatible.rs` + `reliable.rs` 双层设计
- `compatible.rs`：一个 OpenAI 兼容实现覆盖所有支持 OpenAI 协议的提供商（只需改 baseUrl 和认证）
- `reliable.rs`：内置重试（指数退避）和降级（主模型不可用时切备用）
- `router.rs`：多模型路由，可以按策略选择模型
- 28+ 提供商，但核心代码量很少，因为大部分走 `compatible.rs`

**JAVAClaw 选择**：完全学 ZeroClaw 的双层设计

**理由**：
- ZeroClaw 的 `compatible.rs` 模式是天才设计：90% 的模型商都兼容 OpenAI 协议，写一个 `OpenAiCompatibleProvider` 基类就够了，子类只需配置 URL 和认证方式
- `reliable.rs` 的可靠性层是必须的：LLM API 不稳定是常态，没有重试和降级的系统不可用
- OpenClaw 没有明确的可靠性层，这是一个缺陷
- Java 的实现更优雅：`OpenAiCompatibleProvider` 作为抽象基类，子类继承后只需 `@Override` 配置方法；`ReliableProvider` 用装饰器模式包装任何 Provider 加上重试/降级
- 加上 `ProviderRouter` 做多模型路由，和 ZeroClaw 的 `router.rs` 对应

---

## 5. Tool（工具）

**OpenClaw 的做法**：分散式、富媒体导向
- 工具分散在多个独立目录（`src/browser/`、`src/canvas-host/`、`src/cron/`、`src/media/` 等）
- 没有统一的 Tool 接口，各模块独立实现
- 偏"富媒体"：Canvas 可视化工作区、TTS 文字转语音、媒体理解（图片/音频/视频）
- 有通道级插件系统，工具可以通过插件扩展

**ZeroClaw 的做法**：集中式、系统级导向
- 所有工具集中在 `src/tools/` 下，每个工具一个 `.rs` 文件
- `traits.rs` + `schema.rs` 统一抽象：所有工具实现同一个 Tool trait
- 偏"系统级"：硬件交互、Git 操作、代理配置
- 30 个工具，但接口统一，新增工具只需实现 trait

**JAVAClaw 选择**：ZeroClaw 的集中式统一接口 + OpenClaw 的审批思路

**理由**：
- ZeroClaw 的统一 `Tool` trait 是正确做法：所有工具一个接口（`name()`、`description()`、`inputSchema()`、`execute()`），Agent 不需要知道工具的内部实现
- OpenClaw 的分散式组织让工具之间没有统一契约，新增工具要自己搞一套，维护成本高
- 但 OpenClaw 的工具审批思路要学：危险工具（Shell、FileWrite）执行前需要用户确认
- Java 的做法：`Tool` 接口 + `ToolRegistry` 注册中心 + `@DangerousOperation` 注解标记危险工具，Security 模块拦截需要审批的调用
- MVP 阶段只做 5 个核心工具（Shell、FileRead、FileWrite、HttpRequest、WebSearch），不做富媒体

---

## 6. Memory（记忆系统）

**OpenClaw 的做法**：插件槽位
- 记忆系统是一个插件槽位，同时只运行一个实现
- 具体存储后端由插件决定
- 没有明确的向量搜索或混合搜索能力
- 灵活但深度不够——把记忆当"可选功能"而非"核心能力"

**ZeroClaw 的做法**：自研全栈搜索引擎
- 自己实现了完整的混合搜索：向量搜索（SQLite BLOB + 余弦相似度）+ 关键词搜索（FTS5 + BM25）
- `lucid.rs` 做加权合并：`score = α * vectorScore + (1-α) * keywordScore`
- 支持 4 种存储后端：SQLite（默认）、PostgreSQL、Markdown 文件、无存储
- 有文本分块（`chunker.rs`）、嵌入生成（`embeddings.rs`）、响应缓存（`response_cache.rs`）
- 零外部依赖，全部自研

**JAVAClaw 选择**：学 ZeroClaw 的混合搜索思路，但用 Lucene 替代自研

**理由**：
- ZeroClaw 把记忆当核心能力是对的——AI 助手没有记忆就是"金鱼"，每次对话都从零开始
- OpenClaw 把记忆当插件太轻视了，记忆应该是内置的核心模块
- 但 ZeroClaw 用 SQLite 自研向量搜索有局限：余弦相似度在大数据量下性能差，FTS5 的中文分词也不好
- Java 有 Lucene——这是世界上最成熟的搜索引擎库，天然支持向量搜索（KNN）+ BM25 关键词搜索 + 中文分词
- 用 Lucene 实现混合搜索比 ZeroClaw 的自研方案更强、更可靠、更省代码
- 会话历史用 PostgreSQL 存（结构化数据），长期记忆用 Lucene 索引（检索优化），两层分离

---

## 7. Security（安全）

**OpenClaw 的做法**：信任主 session 模型
- DM 配对码认证：未知发送者需要输入配对码
- 主 session 在宿主机直接执行，群聊 session 可沙箱
- 有白名单机制（`allowlists/` 目录）
- MCP 通过 mcporter 桥接，不直接内置
- 安全做得"够用"，但不深入

**ZeroClaw 的做法**：纵深防御
- 6 位一次性配对码 + Bearer Token（比 OpenClaw 更规范）
- 4 种沙箱可选：Bubblewrap、Firejail、Landlock（Linux 内核级）、Docker
- `detect.rs` 自动检测系统可用的沙箱，选最强的
- 文件系统作用域：14 个系统目录 + 4 个 dotfile 被屏蔽
- Symlink 逃逸检测（通过 canonicalization）
- `audit.rs` 审计日志、`secrets.rs` 密钥管理
- 默认拒绝一切，需显式 opt-in

**JAVAClaw 选择**：偏 ZeroClaw 的纵深防御，但沙箱只做 Docker

**理由**：
- ZeroClaw 的"默认拒绝"原则是对的：AI 助手能执行系统命令，安全必须是第一优先级
- OpenClaw 的"信任主 session"太乐观了——一个提示词注入就可能让 Agent 执行恶意命令
- 但 ZeroClaw 的 4 种沙箱对 Java 来说太重了：Bubblewrap/Firejail/Landlock 都是 Linux 特有的，Java 跨平台运行不能依赖这些
- JAVAClaw 只做 Docker 沙箱——跨平台、成熟、Java 有官方 Docker SDK
- 配对认证学 ZeroClaw：6 位一次性码 + Bearer Token，比 OpenClaw 的 DM 配对更规范
- 审计日志必须有：所有工具调用、LLM 请求、认证事件都写日志
- 工具审批流程：危险操作弹出确认，用户同意后才执行（结合 Tool 模块的 `@DangerousOperation`）

---

## 8. Plugin System（插件系统）

**OpenClaw 的做法**：npm 运行时插件
- `src/plugins/` + `src/plugin-sdk/` 提供完整的插件 SDK
- 运行时动态加载 npm 包作为插件
- 通道级插件（`channels/plugins/`）可以扩展消息处理
- ClawHub 技能注册中心，类似"应用商店"
- MCP 通过 mcporter 桥接，可热插拔
- 灵活，但有安全风险（npm 包可能包含恶意代码）和稳定性风险（版本冲突）

**ZeroClaw 的做法**：编译时 Trait 替换
- 没有运行时插件系统
- 所有扩展通过实现 Rust trait，编译时确定
- `skillforge/` + `skills/` 提供技能系统，但也是编译时的
- 安全稳定，但不够灵活——用户想加个新 Channel 得重新编译

**JAVAClaw 选择**：Java SPI — 两者的中间路线

**理由**：
- OpenClaw 的 npm 运行时插件太危险：npm 生态的供应链攻击是现实威胁，AI 助手加载不可信代码风险极高
- ZeroClaw 的编译时 trait 太死板：普通用户不会编译 Rust，扩展门槛太高
- Java 的 SPI（ServiceLoader）是天然的中间方案：运行时加载 JAR 包，但 JAR 包是编译好的 Java 类，比 npm 包更可控
- 具体做法：用户把插件 JAR 放入 `~/.javaclaw/plugins/` 目录，通过 `META-INF/services/` 声明实现类，启动时自动扫描加载
- 4 个可扩展点：`ModelProvider`、`ChannelAdapter`、`Tool`、`MemoryStore`——和 ZeroClaw 的 trait 一一对应，但不需要重新编译
- 比 OpenClaw 安全：JAR 包是字节码，不像 npm 那样可以执行任意脚本；未来可加 ClassLoader 隔离

---

## 9. Observability（可观测性）

**OpenClaw 的做法**：基本缺失
- 没有明确的可观测性模块
- 没有成本追踪、没有诊断工具、没有健康检查
- 依赖外部工具（如日志文件）来排查问题

**ZeroClaw 的做法**：内置全套
- `cost/` 模块：记录每次 LLM 调用的 token 数 × 单价，按日/月汇总
- `health/` + `heartbeat/`：健康检查和心跳监控
- `doctor/`：诊断工具，自检环境、连接、配置状态
- `observability/`：可观测性模块（指标、追踪）

**JAVAClaw 选择**：学 ZeroClaw 的内置全套，用 Java 生态实现

**理由**：
- LLM 调用很贵，没有成本追踪就是"盲飞"——你不知道每天花了多少钱，哪个模型最费钱
- OpenClaw 完全没做这块，是一个明显的缺陷
- ZeroClaw 做了，但受限于 Rust 生态，自己实现了很多轮子
- Java 生态在可观测性上是最强的：Micrometer（指标）+ OpenTelemetry（追踪）都是行业标准
- 具体做法：
  - 成本追踪：每次 LLM 调用记录 token 数 × 单价，Micrometer 暴露指标
  - 健康检查：Spring Boot Actuator 自带 `/health` 端点
  - 诊断工具：`/doctor` 命令自检环境（JDK 版本、Docker 可用性、数据库连接、LLM API 可达性）
  - 全链路追踪：OpenTelemetry 追踪请求从 Channel → Gateway → Agent → Provider 的完整路径

---

## 10. 总结：决策一览表

| 模块 | OpenClaw 做法 | ZeroClaw 做法 | JAVAClaw 选择 | 偏向 |
|------|--------------|--------------|--------------|------|
| Gateway | 重量级控制平面（160+ 文件） | 极简薄层（单文件） | 薄层 + 事件广播/限流 | ZeroClaw |
| Channel | 多层抽象 + 通道级插件 | 扁平结构 + 单 trait | 扁平结构 + 统一白名单 | ZeroClaw |
| Agent | RPC 独立进程 | 内嵌主循环 + 意图分类 | 内嵌循环 + 分类器 | ZeroClaw |
| Provider | 各自独立实现 | compatible.rs + reliable.rs | 兼容基类 + 可靠性装饰器 | ZeroClaw |
| Tool | 分散式、富媒体导向 | 集中式、统一 trait | 集中式统一接口 + 审批注解 | ZeroClaw + OpenClaw 审批 |
| Memory | 插件槽位（浅） | 自研混合搜索引擎（深） | Lucene 混合搜索（更强） | ZeroClaw 思路 + Java 优势 |
| Security | 信任主 session | 纵深防御（4 种沙箱） | 纵深防御（Docker 沙箱） | ZeroClaw |
| Plugin | npm 运行时插件 | 编译时 trait | Java SPI 运行时加载 | 两者中间路线 |
| Observability | 基本缺失 | 内置全套（自研） | 内置全套（Java 生态） | ZeroClaw 思路 + Java 优势 |

**总体倾向**：9 个模块中，7 个偏 ZeroClaw，2 个取两者之长。ZeroClaw 的设计哲学（接口驱动、职责清晰、安全优先）更适合 JAVAClaw，但 Java 生态在记忆系统（Lucene）、插件系统（SPI）、可观测性（Micrometer/OTel）上有独特优势。

---

## 11. 架构审查与修复

> 初版架构设计中发现的 7 个问题及修复方案，按优先级排列。

### [P0-1] 安全上线顺序有窗口期

**问题**：ShellTool 在 Phase 3 上线，但 Docker 沙箱在 Phase 6，等于高危工具先裸跑 3 个阶段。

**修复**：沙箱与工具同步上线。
- Phase 3 新增 `ToolExecutor` 接口，所有工具通过它执行
- `DockerExecutor`：Docker 可用时，`docker run --rm --network=none` 隔离执行
- `RestrictedNativeExecutor`：Docker 不可用时的降级方案——工作目录白名单、命令黑名单、执行超时、输出截断
- Phase 6 变为"沙箱加固"：资源限制、自动检测、策略配置

**Phase 路线变更**：
```
Phase 3（原）: ShellTool + FileReadTool
Phase 3（改）: ShellTool + FileReadTool + ToolExecutor（基础沙箱）
Phase 6（原）: Docker 沙箱执行
Phase 6（改）: 沙箱加固（资源限制、自动检测、策略配置）
```

---

### [P0-2] 插件安全边界不成立

**问题**："JAR 比 npm 安全"这个前提不够，进程内加载第三方 JAR 依然可拿到宿主进程的全部权限。

**修复**：插件双轨制。
- **Track A（内置/可信）**：SPI 进程内加载，用于官方插件和用户自己写的插件
- **Track B（第三方/不可信）**：独立 JVM 子进程运行，通过 stdin/stdout JSON-RPC 通信
- Track B 插件跑在受限环境中，通过 `ProcessBuilder` 控制资源
- 插件清单 `plugin.yaml` 声明权限需求（网络、文件系统、Shell），加载时用户确认
- 默认所有第三方插件走 Track B，用户可手动"信任"升级到 Track A

```
~/.javaclaw/plugins/
├── trusted/       # Track A: SPI 进程内加载
└── sandboxed/     # Track B: 子进程隔离运行
```

---

### [P1-3] 并发模型混用

**问题**：同时用了 Virtual Threads、Flux、同步 `Tool.execute()`，后续会有取消/背压/阻塞语义冲突。

**修复**：统一为 Virtual Threads 同步模型，Flux 只用在对外边界。
- 内部一律同步阻塞：`Tool.execute()`、`Provider.chat()`、`MemoryStore.recall()` 全部同步，跑在 Virtual Threads 上
- Flux 只出现在两个边界：
  1. `AgentOrchestrator.runStream()` — 对外推送流式事件给 WebSocket/SSE
  2. `ModelProvider.chatStream()` — 接收 LLM 的流式 token
- 桥接：Provider 内部用 `BlockingQueue` 把流式 token 转为同步 `Iterator<ChatEvent>`，Agent Loop 同步消费
- 取消：Virtual Thread 用 `Thread.interrupt()`，边界处转换为 `Disposable.dispose()`

```
客户端 ←[Flux]← Gateway ←[同步/VT]← Agent Loop ←[Iterator]← Provider ←[Flux]← LLM API
         边界1                                                  边界2
```

---

### [P1-4] PostgreSQL + Lucene 的一致性没定义

**问题**：会话存储和索引更新没有明确事务边界、补偿和重建策略。

**修复**：最终一致 + 异步索引 + 重建机制。
- PostgreSQL 是 source of truth，Lucene 是派生索引
- 写入顺序：先写 PG（事务提交）→ 再异步更新 Lucene
- 异步队列：`index_queue` 表记录待索引的 `memory_id`，后台线程消费并更新 Lucene
- 失败补偿：后台定时扫描 `index_queue` 中未完成的条目重试
- 全量重建：`/admin/reindex` 命令，启动时检测索引版本号，不匹配则自动重建
- 不追求强一致：记忆检索晚几秒更新完全可接受

```
store() → PG.insert(事务) → indexQueue.offer(memoryId) → [异步] Lucene.index()
                                                              ↓ 失败
                                                         index_queue 表重试

启动时 → 检查 index_version → 不匹配 → 全量 reindex
```

---

### [P1-5] 混合检索打分过于理想化

**问题**：`α*vector + (1-α)*keyword` 直接线性加权，向量分数和 BM25 分数量纲、分布不一致，召回质量不稳定。

**修复**：改用 Reciprocal Rank Fusion (RRF)。
- RRF 只依赖排名不依赖原始分数，天然解决量纲问题
- 公式：`RRF_score(d) = 1/(k + rank_vector(d)) + 1/(k + rank_keyword(d))`，k=60
- 如果某文档只在一个列表中出现，另一个 rank 设为大数（如 1000）
- 两个搜索各返回 Top-N 带排名，合并后按 RRF 分数重排，取 Top-K

```java
double rrf(int vectorRank, int keywordRank) {
    return 1.0 / (60 + vectorRank) + 1.0 / (60 + keywordRank);
}
```

---

### [P2-6] 配置热更新方案偏理想

**问题**：`@RefreshScope` 不能自动解决本地配置热重载和安全策略一致性问题。

**修复**：分层配置 + 自研 ConfigWatcher，不依赖 Spring 魔法。
- 配置分三级：
  - **静态**（改了要重启）：数据库连接、端口、沙箱类型、插件目录
  - **热更新**（运行时可改）：模型选择、Channel 启用/禁用、工具权限、提示词模板
  - **安全策略**（热更新但需确认）：白名单、审批规则、限流阈值——变更需配对码二次验证
- `ConfigWatcher` 用 `WatchService` 监听 `~/.javaclaw/config.yaml` 变更，解析后通知订阅者
- 可热更新的组件实现 `Reconfigurable` 接口：

```java
public interface Reconfigurable {
    void onConfigChange(ConfigDiff diff);
}
```

---

### [P2-7] Provider 可靠性设计不完整

**问题**：有重试/降级，但缺少超时预算、熔断、限流联动、幂等键等工程化要素。

**修复**：补全完整的可靠性工程。

- **超时预算**：每个请求有总超时（如 120s），重试共享预算
  ```
  第1次: timeout = min(30s, remaining)
  第2次: timeout = min(30s, remaining - elapsed)
  预算用完 → 直接降级，不再重试
  ```
- **熔断**：简单计数器熔断
  ```
  连续失败 ≥ 3 次 → 熔断打开 → 直接走降级模型
  每 60s 放一个探测请求 → 成功则关闭熔断
  ```
- **限流联动**：记录每个模型的 RPM/TPM 消耗，接近限额时主动切换备用模型，不等 429
- **幂等键**：非流式请求生成 `idempotency_key = hash(model + messages)`，命中缓存直接返回，避免重试重复计费
- **降级链**：有序降级列表，不只是"主+备"

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
