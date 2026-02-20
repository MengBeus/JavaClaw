# JAVAClaw 问题记录

按 Phase / Step 分段，记录开发过程中出现的问题及解决方案。

---

## Phase 1：骨架搭建

### Step 2：shared 公共模型

**问题 1：OutboundMessage record 缺少 `{}`**

- 文件：`src/main/java/com/javaclaw/shared/model/OutboundMessage.java`
- 错误：`error: '{' expected` — record 定义以 `)` 结尾，缺少 `{}`
- 解决：补上 `) {}` 闭合体

**问题 2：ConfigLoader.envOrDefault 返回类型错误**

- 文件：`src/main/java/com/javaclaw/shared/config/ConfigLoader.java:55`
- 错误：`error: incompatible types: int cannot be converted to String` — `envOrDefault()` 返回 `int`，但 database 的 url/username/password 需要 `String`
- 解决：`envOrDefault()` 改为返回 `String`，port 处单独用 `Integer.parseInt()` 包装

**问题 3：apiKeys 未从配置加载**

- 文件：`src/main/java/com/javaclaw/shared/config/ConfigLoader.java:51`
- 说明：`apiKeys` 参数写死为 `Map.of()`，未从 `config.yaml` 的 `api-keys` 节读取
- 解决：新增解析 `api-keys` 节，遍历写入 `HashMap<String, String>`

### Step 3：Gateway 启动入口

**问题 4：入口类命名不当**

- 文件：`src/main/java/com/javaclaw/gateway/GatewayApp.java`
- 说明：单模块项目中用 `GatewayApp` 命名不准确，它代表整个应用而非仅 gateway
- 解决：重命名为 `JavaClawApp`

**问题 5：WebSocket 路径硬编码**

- 文件：`src/main/java/com/javaclaw/gateway/ws/WebSocketConfig.java:19`
- 说明：`/ws` 路径写死在代码中，无法通过配置修改
- 解决：用 `@Value("${javaclaw.ws.path:/ws}")` 从 `application.yml` 读取

### Step 6：验证

**问题 8：本地 PostgreSQL 与 Docker 端口冲突导致鉴权失败**

- 现象：`mvn spring-boot:run` 启动时报 `FATAL: password authentication failed for user "javaclaw"`
- 根因：本地 PostgreSQL 服务占用了 `5432`，应用实际连接到了本地实例，而不是 Docker 容器里的 `project2-postgres-1`
- 解决：
  - 关闭本地 PostgreSQL 服务或进程，确保 `5432` 由 Docker 映射处理
  - 保留 `application.yml` 中 `jdbc:postgresql://localhost:5432/javaclaw`

**问题 9：Git Bash 环境下 `python3` 命中 WindowsApps 占位符**

- 文件：`scripts/phase1-verify.sh`
- 现象：脚本在 `[4/5] POST /v1/chat` 阶段失败，`python3` 返回码 `49`
- 根因：`python3` 指向 `/c/Users/.../WindowsApps/python3` 占位路径，不是可执行 Python
- 解决：解释器探测从“只检查 `command -v`”改为“`command -v` + `--version` 可执行校验”，不可用时回退到 `python`

**问题 10：`npx wscat` 在当前环境不可执行**

- 文件：`scripts/phase1-verify.sh`
- 现象：WebSocket 兜底校验报错 `execvpe(/bin/bash) failed: No such file or directory`
- 根因：当前终端环境下 `npx` 调起 `wscat` 依赖 `/bin/bash`，与本机 WSL/Git Bash 路径不一致
- 解决：WebSocket 校验策略调整为：
  - 优先 Python `websockets`
  - 兜底 `wscat`
  - 两者不可用时输出 `SKIP`，不阻断 Phase 1 验证流程

### 环境配置

**问题 6：PATH 中 `%JAVA_HOME%` 未展开**

- 说明：通过 PowerShell 写入 PATH 时用了 `%JAVA_HOME%\bin`，PowerShell 不解析 `%` 语法，导致路径无效
- 错误：`java : The term 'java' is not recognized as the name of a cmdlet`
- 解决：改为写入完整路径 `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin`

**问题 7：JAVA_HOME\bin 未加入 PATH**

- 说明：初次配置时只设了 `JAVA_HOME` 变量和 Maven 路径，漏了 `JAVA_HOME\bin`
- 错误：Maven 报 `The JAVA_HOME environment variable is not defined correctly`
- 解决：将 JDK bin 目录追加到用户 PATH

---

## Phase 2：Agent 闭环

### Step 1：providers（模型后端）

**问题 11：ModelProvider 缺少 chatStream 接口**

- 文件：`src/main/java/com/javaclaw/providers/ModelProvider.java:3`
- 说明：执行计划要求接口包含 `chat, chatStream, id` 三个方法，但实现时遗漏了 `chatStream`
- 解决：新增 `Iterator<ChatEvent> chatStream(ChatRequest request)`，采用 `Iterator` 而非 `Flux`，符合架构文档内部同步模型（`Agent Loop ←[Iterator]← Provider`）
- 联动：`OpenAiCompatibleProvider` 添加最小实现（同步 chat 结果包装为单元素迭代器），`ProviderRouter` 同步实现委托

**问题 12：ProviderRouter 未注册 provider 时空指针**

- 文件：`src/main/java/com/javaclaw/providers/ProviderRouter.java:28`
- 说明：`providers.get(primaryId).chat(request)` 在无注册 provider 或 primaryId 为 null 时直接 NPE
- 解决：提取 `resolve()` 方法统一做空值检查，抛 `IllegalStateException("No provider registered")`

**问题 13：ResilientCall 全局超时压缩重试空间**

- 文件：`src/main/java/com/javaclaw/providers/ResilientCall.java:12-15`
- 说明：使用全局 30s deadline，若首次尝试耗时 25s 失败，剩余重试仅 5s，基本无效。计划语义是"每次尝试 30s + 重试 2 次"
- 解决：移除全局 deadline，改为纯重试计数（最多 2 次重试 + 指数退避 500ms/1s）。单次请求超时由 `OpenAiCompatibleProvider` 的 HTTP client `.timeout(Duration.ofSeconds(30))` 保证

### Step 2：agent（Agent 核心）

**问题 14：多轮对话历史未接入调用链**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:22`
- 说明：`agentLoop.execute(request.message(), null)` 固定传 null 给 history，导致每次调用 LLM 只带 system + 当前 user 消息，无法多轮对话
- 根因：LLM 本身无记忆，需要每次把完整聊天记录（user + assistant 交替）发送过去
- 解决：`DefaultAgentOrchestrator` 内部用 `ConcurrentHashMap<sessionId, List<Message>>` 维护会话历史，每轮调用后追加 user 和 assistant 消息

**问题 15：AgentRequest 的 sessionId/context 被丢弃**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:19-22`
- 说明：代码只使用了 `request.message()`，sessionId 和 context 完全忽略，无法区分不同用户的对话
- 解决：与问题 14 一并修复，用 sessionId 作为会话历史的 key

**问题 16：空消息导致 NPE**

- 文件：`src/main/java/com/javaclaw/agent/PromptBuilder.java:15`
- 说明：`Map.of("role", "user", "content", userMessage)` 在 userMessage 为 null 时抛 NPE
- 解决：`PromptBuilder.build()` 入口校验 null/blank，抛 `IllegalArgumentException("userMessage must not be empty")`

### Step 3：channels（消息通道）

**问题 17：CliAdapter.start() 阻塞导致 startAll() 卡死**

- 文件：`src/main/java/com/javaclaw/channels/CliAdapter.java:24`、`src/main/java/com/javaclaw/channels/ChannelRegistry.java:18`
- 说明：`start()` 实现为前台死循环（`while + readLine()`），调用后不返回。`ChannelRegistry.startAll()` 串行遍历调用各 adapter 的 `start()`，第一个阻塞后，后续 adapter 永远启动不了
- 根因：没有从调用方角度考虑 `start()` 的语义——架构设计意图是"启动监听"（非阻塞），而非"阻塞在这里监听"
- 解决：`start()` 内部用 `Thread.startVirtualThread()` 起虚拟线程跑读循环，`start()` 本身立即返回，符合架构文档 Virtual Threads 并发模型

**问题 18：stop() 无法可靠停止 CLI 监听线程**

- 文件：`src/main/java/com/javaclaw/channels/CliAdapter.java:46`
- 说明：`stop()` 只设 `running = false`，但线程卡在 `readLine()` 阻塞上，感知不到 flag 变化，必须等用户按回车才能退出循环
- 根因：`readLine()` 是操作系统级阻塞 I/O，Java 变量修改无法打断
- 解决：`stop()` 增加 `readThread.interrupt()` 中断阻塞线程，线程抛异常后检查 `running` flag 退出

**问题 19：ChannelRegistry 重复 id 静默覆盖**

- 文件：`src/main/java/com/javaclaw/channels/ChannelRegistry.java:11`
- 说明：`register()` 直接 `put`，相同 id 的 adapter 会被静默覆盖，配置错误时不会暴露
- 解决：注册前检查 `containsKey`，重复则抛 `IllegalArgumentException("Duplicate channel adapter: " + id)`

### Step 4：Gateway 串联

**问题 20：/quit 不能让程序真正退出**

- 文件：`src/main/java/com/javaclaw/channels/CliAdapter.java:33`、`src/main/java/com/javaclaw/gateway/JavaClawApp.java:19`
- 说明：`/quit` 只停了 CLI 读线程，但 Spring Boot 内置的 Tomcat Web 服务器仍在运行（监听 18789 端口），JVM 检测到非守护线程存活，进程不退出
- 解决：CliAdapter 新增 `onStop(Runnable)` 回调，`/quit` 时调用 `registry.stopAll()` + `ctx.close()` 关闭 Spring 上下文
- 遗留：当前 `/quit` 会关闭整个程序，Phase 4 多通道时需改为只停当前通道（见 EXECUTION_PLAN.md Phase 4 备注）

**问题 21：出站通道硬编码为 cli**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:38`
- 说明：`cli.send(...)` 直接引用 CLI adapter 变量，不管消息从哪个通道进来都回复到 CLI。多通道时 Telegram 用户的回复会发到 CLI 终端
- 解决：改为 `registry.get(msg.channelId())` 按入站 channelId 动态查找对应 adapter 回发

**问题 22：DeepSeek API Key 无启动期校验**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:24-25`
- 说明：`getOrDefault("deepseek", "")` 拿到空字符串也照常注册 provider，用户首条消息才收到 401 错误，排查成本高
- 解决：启动时检查 API Key，空则 `log.warn` 提醒用户配置 `~/.javaclaw/config.yaml`

---

## Phase 3：工具 + 会话 + 沙箱

### Step 1：tools + security

**问题 23：执行器超时控制失效（管道死锁）**

- 文件：`src/main/java/com/javaclaw/security/DockerExecutor.java:22-23`、`src/main/java/com/javaclaw/security/RestrictedNativeExecutor.java:23-24`
- 说明：初版 `readAllBytes()` 在 `waitFor()` 之前，阻塞到进程结束，超时判断形同虚设。修复后改为先 `waitFor()` 再 `readAllBytes()`，但引入管道缓冲区死锁——进程输出超过 64KB 时卡在写操作上，`waitFor()` 永远等不到进程结束
- 根因：`waitFor()` 和 `readAllBytes()` 不能串行，必须并行消费 stdout
- 解决：用 `Thread.startVirtualThread()` 起异步线程持续读 stdout（`transferTo(ByteArrayOutputStream)`），主线程 `waitFor(timeout)` 等进程结束，超时则 `destroyForcibly()` 杀进程

**问题 24：RestrictedNativeExecutor 缺少工作目录白名单**

- 文件：`src/main/java/com/javaclaw/security/RestrictedNativeExecutor.java:12`
- 说明：架构文档要求降级执行器具备"白名单 + 黑名单 + 超时"三要素，实现时只有命令黑名单和超时，漏了工作目录白名单
- 解决：构造参数新增 `Set<String> allowedDirs`，执行前校验 workDir 的 normalize 路径是否以白名单中某项开头，不在白名单则返回 `[BLOCKED]`

**问题 25：文件工具可绕过工作目录边界（路径穿越）**

- 文件：`src/main/java/com/javaclaw/tools/FileReadTool.java:31`、`src/main/java/com/javaclaw/tools/FileWriteTool.java:32`
- 说明：`Path.of(workDir, inputPath)` 在 inputPath 为绝对路径时直接忽略 workDir，`..` 也未过滤，可读写任意文件
- 解决：先 `base.resolve(inputPath).normalize()`，再 `startsWith(base)` 校验最终路径是否在工作目录内，越界返回 `isError=true`

**问题 26：ShellTool 对执行失败信号未标记 isError**

- 文件：`src/main/java/com/javaclaw/tools/ShellTool.java:36`
- 说明：执行器返回 `[BLOCKED]`/`[TIMEOUT]` 字符串表示失败，但 ShellTool 固定返回 `isError=false`，Agent Loop 会把拦截信息当正常输出传给 LLM
- 解决：检测返回字符串前缀，`[BLOCKED]` 或 `[TIMEOUT]` 开头标记 `isError=true`

**问题 27：本地执行器硬编码 bash -c**

- 文件：`src/main/java/com/javaclaw/security/RestrictedNativeExecutor.java:19`
- 说明：写死 `bash -c`，纯 Windows 环境（无 Git Bash）下不可用
- 解决：检测 `os.name`，Windows 用 `cmd /c`，其他系统用 `bash -c`

**问题 28：Tool 接口签名与架构文档不一致**

- 文件：`src/main/java/com/javaclaw/tools/Tool.java`
- 说明：`inputSchema()` 返回 `String` 而非 `JsonNode`；`execute()` 参数顺序反了且入参类型为 `String` 而非 `JsonNode`；`ToolContext` 缺少权限字段
- 解决：`inputSchema()` 改返回 `JsonNode`，`execute(ToolContext ctx, JsonNode input)` 对齐架构文档，`ToolContext` 新增 `Set<String> permissions`

**问题 29：文件工具符号链接越界**

- 文件：`src/main/java/com/javaclaw/tools/FileReadTool.java:31`、`src/main/java/com/javaclaw/tools/FileWriteTool.java:32`
- 说明：问题 25 的 `normalize() + startsWith()` 是词法校验，不解析符号链接。工作目录内若存在指向外部的 symlink，仍可通过 symlink 读写目录外文件
- 解决：改用 `toRealPath()` 解析符号链接后再做 `startsWith` 校验。FileWriteTool 因目标文件可能不存在，改为校验父目录的 `toRealPath()`

**问题 30：命令非 0 退出码未标记为错误**

- 文件：`src/main/java/com/javaclaw/security/RestrictedNativeExecutor.java:47`、`src/main/java/com/javaclaw/security/DockerExecutor.java:32`、`src/main/java/com/javaclaw/tools/ShellTool.java:36`
- 说明：执行器只返回输出字符串，不传递退出码；ShellTool 只检查 `[BLOCKED]`/`[TIMEOUT]` 前缀，命令执行失败（如 `ls nonexistent` 返回码 2）仍标记 `isError=false`
- 解决：新增 `ExecutionResult(output, exitCode)` record，`isError()` 统一判断退出码非 0 或 BLOCKED/TIMEOUT 前缀。`ToolExecutor` 接口返回类型从 `String` 改为 `ExecutionResult`

**问题 31：DockerExecutor.isAvailable() 管道死锁**

- 文件：`src/main/java/com/javaclaw/security/DockerExecutor.java:43-44`
- 说明：`isAvailable()` 仍用旧模式——先 `readAllBytes()` 再 `waitFor(5s)`。`docker info` 输出量大时同样会触发管道缓冲区死锁，探测卡住
- 解决：与 `execute()` 同样模式，虚拟线程异步消费 stdout（丢弃到 `nullOutputStream()`），主线程 `waitFor(5s)` 超时则 `destroyForcibly()`

**问题 32：FileWriteTool 符号链接文件写入越界**

- 文件：`src/main/java/com/javaclaw/tools/FileWriteTool.java:39`
- 说明：问题 29 修复时 FileWriteTool 只校验了父目录的 `toRealPath()`，未校验目标文件本身。若工作目录内存在一个指向外部的 symlink 文件，父目录校验通过（父目录确实在工作目录内），但 `Files.writeString()` 会跟随符号链接写入外部文件
- 解决：写入前增加 `Files.exists(target) && !target.toRealPath().startsWith(base)` 检查，若目标文件已存在且其真实路径不在工作目录内，返回 `isError=true`

**问题 33：FileWriteTool 仍存在“先产生副作用再拦截”的窗口**

- 文件：`src/main/java/com/javaclaw/tools/FileWriteTool.java`
- 说明：旧实现在完成全部边界校验前就可能执行 `createDirectories`，当输入路径越界时虽然最终返回错误，但可能已在工作目录外创建目录（副作用已发生）。
- 解决：将所有路径/边界校验前置（`target.startsWith(base)`、`parent.startsWith(base)`、最近已存在祖先 real path 校验、目标符号链接校验），只有全部通过后才允许创建目录和写入。
- 加固：写入时改为 `Files.newOutputStream(..., LinkOption.NOFOLLOW_LINKS)`，拒绝跟随目标符号链接。

**问题 34：tools/security 测试覆盖不足**

- 文件：`src/test/java/com/javaclaw/tools/FileWriteToolTest.java`、`src/test/java/com/javaclaw/tools/ShellToolTest.java`
- 说明：此前仅有 `PromptBuilder/ResilientCall` 等测试，缺少工具层关键安全行为回归测试。
- 解决：新增最小测试集：
  - `FileWriteToolTest`：验证工作目录内正常写入、越界路径被拒且不会产生目录副作用。
  - `ShellToolTest`：验证非 0 退出码标记 `isError=true`，0 退出码标记 `isError=false`。

### Step 2：Agent 支持 tool_call 多轮循环

**问题 35：ToolContext.sessionId 固定为 null**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:22`
- 说明：ToolContext 在构造函数中一次性创建 `new ToolContext(workDir, null, Set.of())`，sessionId 写死 null。后续每次 `run()` 都复用同一个上下文，工具拿不到真实会话 ID
- 根因：生命周期错配——ToolContext 是请求粒度数据，却在应用启动粒度构造
- 解决：AgentLoop 只存 workDir，`execute()` 接收 sessionId 参数，`executeTool()` 内部按需构建 ToolContext

**问题 36：工具执行失败状态在 AgentLoop 中丢失**

- 文件：`src/main/java/com/javaclaw/agent/AgentLoop.java:90-91`
- 说明：`executeTool()` 只返回 `result.output()`，`ToolResult.isError` 被丢弃。LLM 只看到文本，无法区分成功与失败，会把错误结果当正常输出继续推理
- 根因：信息在层间传递时被截断，只传了文本没传状态
- 解决：`isError()` 为 true 时统一加 `[ERROR] ` 前缀，让 LLM 明确识别失败

**问题 37：executeTool() 对 toolRegistry 缺少判空**

- 文件：`src/main/java/com/javaclaw/agent/AgentLoop.java:58、86`
- 说明：`buildToolsDef()` 对 `toolRegistry == null` 做了处理（不发工具定义），但 `executeTool()` 直接调 `toolRegistry.get(name)`。LLM 行为不可控，若仍返回 tool_call 则 NPE
- 根因：防御逻辑只做了一半，未在每个消费点校验外部输入
- 解决：`executeTool()` 入口增加 `toolRegistry == null` 检查，返回 `[ERROR] No tools registered`

### Step 3：会话持久化

**问题 38：会话元信息 user_id/channel_id 永远为 null**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:35`
- 说明：`sessionStore.save(sessionId, null, null, history)` 写死 null，sessions 表的 user_id 和 channel_id 列永远为空
- 根因：AgentRequest.context 里有 senderId/channelId 信息，但 Orchestrator 没提取
- 解决：JavaClawApp 在构造 AgentRequest 时将 userId/channelId 放入 context Map，Orchestrator 提取后传给 save

**问题 39：tool_call 过程消息未持久化**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:33-34`
- 说明：save 前只追加了 user + final assistant 两条消息，AgentLoop 运行中产生的 assistant(tool_calls) 和 tool result 消息被丢弃。重启后这部分上下文丢失，LLM 无法理解之前的工具交互
- 根因：消息追加逻辑在 Orchestrator 而非 AgentLoop，Orchestrator 看不到循环内部的中间消息
- 解决：AgentLoop.execute() 直接往 history 列表追加所有消息（user、assistant+tool_calls、tool results、final assistant），Orchestrator 只负责 load + save

**问题 40：delete-all + reinsert 并发覆盖风险**

- 文件：`src/main/java/com/javaclaw/sessions/PostgresSessionStore.java:29-30`
- 说明：save 事务内先 DELETE 全部消息再 INSERT，同一 sessionId 并发请求时后提交事务覆盖先提交结果（last-write-wins）
- 解决：upsert session 后增加 `SELECT id FROM sessions WHERE id = ? FOR UPDATE` 行级锁，序列化同一 session 的并发写入

### Step 4：工具审批

**问题 41：CLI 审批输入与主循环共用 System.in 读入冲突**

- 文件：`src/main/java/com/javaclaw/approval/CliApprovalStrategy.java:13`、`src/main/java/com/javaclaw/channels/CliAdapter.java:32`
- 说明：CliAdapter 在读线程持有一个 BufferedReader（reader A），CliApprovalStrategy 又 `new BufferedReader(new InputStreamReader(System.in))` 创建第二个（reader B）。两个独立 BufferedReader 读同一 System.in，各自维护独立 8KB 缓冲区，互相抢数据
- 触发场景：Agent 调用 `file_write` 等 @DangerousOperation 工具时，reader B 调 `readLine()` 等审批输入，同时 reader A 的主循环也回到 `readLine()` 等待。用户输入 `y` 后，操作系统将字节交给 JVM，但两个 reader 都在等——若 reader A 先抢到，`y` 被当成新聊天消息发给 Agent，审批端永远收不到回复导致卡死；若 reader B 抢到，审批通过但后续正常消息可能被 reader B 残留缓冲区干扰
- 根因：System.in 是单例字节流，多个 BufferedReader 包装同一流会产生竞争读取，结果不确定
- 解决：JavaClawApp 创建一个共享 `BufferedReader(new InputStreamReader(System.in))`，同时传给 CliAdapter（构造参数）和 CliApprovalStrategy（构造参数），CliAdapter 不再内部创建 reader

**问题 42：DefaultAgentOrchestrator 假设 request.context() 非空**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:34`
- 说明：`request.context().get("userId")` 未做空保护，context 为 null 时 NPE
- 解决：提取 context 时先判空，null 则用空 Map 兜底
