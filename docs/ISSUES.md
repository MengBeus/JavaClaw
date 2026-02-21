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

### Step 5：沙箱三级策略

**问题 43：docker.isAvailable() 被调用两次，执行器与审批分支可能不一致**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:52、62`
- 说明：executor 选择和 Tier 判断各调一次 `docker.isAvailable()`，两次调用间 Docker 状态可能变化，导致执行器用了 Docker 但审批走了无沙箱分支，或反过来
- 解决：首次调用结果缓存到 `dockerAvailable` 变量，后续统一复用

### 集成测试

**问题 44：集成脚本对运行环境假设较强**

- 文件：`scripts/phase2-verify.sh:1,24`、`scripts/phase3-verify.sh:1,25,64`
- 说明：脚本依赖 bash + mkfifo，phase3 还依赖 psql，非 Bash 环境（纯 Windows cmd）无法运行
- 解决：脚本入口加前置检查，缺少命令时立即报错退出

**问题 45：FileReadToolTest 路径穿越断言力度偏弱**

- 文件：`src/test/java/com/javaclaw/tools/FileReadToolTest.java:34`
- 说明：原用例用不存在的 `../../etc/passwd`，`toRealPath()` 直接抛异常走 catch 分支，isError=true 是因为文件不存在而非边界拦截
- 解决：在 tempDir 外创建真实文件，穿越读取时断言错误消息包含 "Path escapes working directory"

**问题 46：phase3-verify.sh 表检查未限定 schema**

- 文件：`scripts/phase3-verify.sh:65,74`
- 说明：只按 table_name 查 information_schema.tables，同名表在其他 schema 也会误判通过
- 解决：SQL 加 `table_schema='public'` 条件

---

## Phase 4：消息平台

### Step 1：TelegramAdapter

**问题 47：CLI /quit 关闭整个进程，多通道共存失效**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:92-93`
- 说明：`onStop` 回调直接 `registry.stopAll()` + `ctx.close()`，CLI 退出时 Telegram 也被停掉
- 解决：改为 `registry.unregister(cli.id())`，仅当 `registry.allStopped()` 时才关闭 Spring 上下文。ChannelRegistry 新增 `unregister()` 和 `allStopped()` 方法

**问题 48：Telegram 匿名管理员消息触发 NPE**

- 文件：`src/main/java/com/javaclaw/channels/TelegramAdapter.java:52`
- 说明：`msg.getFrom().getId()` 在匿名管理员/系统消息时 `getFrom()` 返回 null，NPE 打断消费线程
- 解决：`consume()` 入口增加 `if (msg.getFrom() == null) return;`

**问题 49：Telegram 启动失败被吞掉，假上线**

- 文件：`src/main/java/com/javaclaw/channels/TelegramAdapter.java:43-45`
- 说明：`catch` 只记日志不抛出，JavaClawApp 已注册 adapter 并打印 enabled，实际未成功 long polling
- 解决：改为 `throw new RuntimeException("Failed to start Telegram bot", e)`

**问题 50：ChannelRegistry 并发读写数据竞争**

- 文件：`src/main/java/com/javaclaw/channels/ChannelRegistry.java:8`
- 说明：`LinkedHashMap` 无同步保护，`unregister()` 写操作与消息处理路径的 `get()` 读操作并发，行为未定义
- 解决：`LinkedHashMap` 改为 `ConcurrentHashMap`

### Step 3：auth（配对认证 + 白名单）

**问题 51：配对码未校验目标通道，跨通道配对漏洞**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:130-132`
- 说明：`consumeCode()` 返回的 channel 未与当前 adapterId 比对，给 discord 生成的码能在 telegram 使用
- 解决：`consumeCode` 改为 `consumeCode(code, expectedChannel)`，内部用 `ConcurrentHashMap.remove(key, value)` 原子校验通道匹配

**问题 52：已授权用户发送纯 6 位数字消息被吞**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:129`
- 说明：所有非 CLI 消息先拦截 `\d{6}`，已授权用户发"123456"也走配对分支并 return，正常业务消息丢失
- 解决：逻辑重构为先查白名单，已授权用户直接放行；只有未授权用户的消息才进入配对码判断

**问题 53：配对码碰撞覆盖，旧码静默失效**

- 文件：`src/main/java/com/javaclaw/auth/PairingService.java:14-15`
- 说明：随机 6 位码直接 `put`，未检测已存在，小概率覆盖他人待消费码
- 解决：改为 `putIfAbsent` + 循环重试，确保不覆盖

**问题 54：配对码在通道不匹配时也被消费**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:131-132`
- 说明：先 `consumeCode()` 移除码，再校验 channel，通道不匹配时码已被删除，正确平台无法再用
- 解决：`PairingService.consumeCode(code, expectedChannel)` 用 `ConcurrentHashMap.remove(key, value)` 原子操作，不匹配时不消费

### Step 4：通道审批策略

**问题 55：ApprovalStrategy 接口变更导致测试编译失败**

- 文件：`src/test/java/com/javaclaw/agent/AgentLoopTest.java:74`、`src/test/java/com/javaclaw/approval/ApprovalInterceptorTest.java:44,51,58,59`
- 说明：`ApprovalStrategy.approve()` 从二参改为三参（加 channelId），测试中 lambda 仍用旧签名，`mvn clean test` 编译失败
- 解决：所有测试 lambda 同步更新为三参签名

**问题 56：Telegram 审批死锁——单线程消费被同步阻塞**

- 文件：`src/main/java/com/javaclaw/channels/TelegramAdapter.java:69`、`src/main/java/com/javaclaw/approval/TelegramApprovalStrategy.java:51`
- 说明：`LongPollingSingleThreadUpdateConsumer` 的 `consume()` 同步调用 `sink.accept()` → agent 走到审批 `future.get(60s)` 阻塞唯一消费线程，按钮回调无法被处理，必定超时拒绝
- 解决：`consume()` 中 `sink.accept()` 改为 `Thread.startVirtualThread()` 异步派发，释放消费线程接收 callback query

**问题 57：Docker 可用时自动放行被通道策略覆盖**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:74,169,174`
- 说明：Tier 1 设了默认放行，但随后无条件注册 telegram/discord 审批策略。`ApprovalInterceptor` 优先用通道策略，绕过默认放行
- 解决：通道审批策略注册加 `if (!dockerAvailable)` 守卫，Docker 可用时不注册

**问题 58：Discord 同类阻塞风险**

- 文件：`src/main/java/com/javaclaw/channels/DiscordAdapter.java:72`、`src/main/java/com/javaclaw/approval/DiscordApprovalStrategy.java:44`
- 说明：`onMessageReceived()` 同步 `sink.accept()` → 审批同步等待，事件线程模型下可能导致审批响应被饿死
- 解决：与 Telegram 同理，`sink.accept()` 改为虚拟线程派发

**问题 59：审批确认未绑定发起人，旁观者可代点**

- 文件：`src/main/java/com/javaclaw/approval/TelegramApprovalStrategy.java:60`、`src/main/java/com/javaclaw/approval/DiscordApprovalStrategy.java:53`
- 说明：`handleCallback` / `handleButtonInteraction` 只按 requestId 完成 future，不校验点击者是否为触发工具调用的用户。群聊场景下旁观者可影响审批结果
- 解决：`ApprovalStrategy.approve()` 签名加 `senderId` 参数，全链路穿透（Orchestrator → AgentLoop → Interceptor → Strategy）。审批时存 `requestId → senderId` 映射，回调时校验点击者 ID 必须匹配发起者

---

### Phase 5 Step 1：Memory — Lucene 混合检索

**问题 60：需要长期记忆存储与检索能力**

- 文件：`src/main/java/com/javaclaw/memory/MemoryStore.java`、`MemoryResult.java`、`LuceneMemoryStore.java`
- 说明：Agent 缺少跨会话记忆能力，无法存储和召回历史知识片段
- 解决：新增 `MemoryStore` 接口（store / recall / forget），`LuceneMemoryStore` 基于 Lucene FSDirectory + SmartChineseAnalyzer 实现，索引目录 `~/.javaclaw/index`

**问题 61：纯向量检索对关键词查询召回不足**

- 文件：`src/main/java/com/javaclaw/memory/HybridSearcher.java`
- 说明：单一向量检索在精确关键词匹配场景下召回率低
- 解决：实现混合检索——KnnFloatVectorQuery（向量）+ BM25（关键词）+ RRF 融合（k=60），取两路排名倒数和作为最终分数

**问题 62：需要 Embedding 向量生成服务**

- 文件：`src/main/java/com/javaclaw/memory/EmbeddingService.java`
- 说明：向量检索依赖文本向量化，需对接 embedding 端点
- 解决：`EmbeddingService` 调用 OpenAI 兼容 `/embeddings` 接口，返回 float[] 向量，失败返回 null 由调用方降级为纯关键词检索

### Phase 5 Step 2：Observability — 成本追踪与诊断

**问题 63：缺少 LLM 调用成本追踪**

- 文件：`src/main/java/com/javaclaw/observability/CostTracker.java`、`src/main/resources/db/migration/V4__llm_usage.sql`
- 说明：无法统计每次 LLM 调用的 token 用量和费用
- 解决：新增 `llm_usage` 表和 `CostTracker`，按 session/provider/model 记录 prompt_tokens、completion_tokens、cost_usd，支持日/月汇总查询

**问题 64：缺少运行时指标收集**

- 文件：`src/main/java/com/javaclaw/observability/MetricsConfig.java`
- 说明：无法监控 LLM 延迟、调用次数、工具执行次数等运行指标
- 解决：基于 Micrometer `SimpleMeterRegistry` 注册 Timer（llm.latency）和 Counter（llm.calls、tool.executions、tokens.total）

**问题 65：缺少系统自检命令**

- 文件：`src/main/java/com/javaclaw/observability/DoctorCommand.java`
- 说明：部署后难以快速排查 PostgreSQL、Embedding 端点、Lucene 索引、Java 版本等依赖状态
- 解决：`DoctorCommand.run()` 依次检查四项依赖，返回 `[OK]`/`[FAIL]`/`[WARN]` 状态报告

---

### Phase 5 Bug Fixes

**问题 66：CostTracker / DoctorCommand 未接入运行链路**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:49`、`src/main/java/com/javaclaw/gateway/JavaClawApp.java:93,158`
- 说明：`CostTracker.record()` 无调用点，`DoctorCommand.run()` 无入口触发，功能不可用
- 解决：`DefaultAgentOrchestrator` 新增 `CostTracker` 构造参数，`run()` 中 LLM 调用后自动记录 usage；JavaClawApp 新增 `/doctor` CLI 命令路由

**问题 67：MemoryStore metadata 契约丢失**

- 文件：`src/main/java/com/javaclaw/memory/LuceneMemoryStore.java:49`、`src/main/java/com/javaclaw/memory/HybridSearcher.java:63`
- 说明：`store()` 未写入 metadata，`recall()` 固定返回空 metadata，接口契约被违反
- 解决：`store()` 将 metadata 序列化为 JSON `StoredField("metadata")`；`HybridSearcher` 召回时反序列化还原

**问题 68：memory / observability 模块缺少测试**

- 文件：`src/test/java/com/javaclaw/memory/LuceneMemoryStoreTest.java`、`src/test/java/com/javaclaw/observability/`
- 说明：新增模块无测试覆盖，回归风险高
- 解决：新增 10 个测试（LuceneMemoryStoreTest 4、MetricsConfigTest 2、CostTrackerTest 2、DoctorCommandTest 2），总测试数 31→41

**问题 69：CostTracker 读取 token 字段名与 provider 返回的 key 不匹配**

- 文件：`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:53`
- 说明：读取 `prompt_tokens`/`completion_tokens`，但 `OpenAiCompatibleProvider` 返回的 usage key 是 `promptTokens`/`completionTokens`，`getOrDefault` 总走默认值 0
- 解决：改为读取 `promptTokens`/`completionTokens`

**问题 70：CostTracker 定价表 model 名与 provider.id() 不匹配**

- 文件：`src/main/java/com/javaclaw/observability/CostTracker.java:19`
- 说明：定价表 key 为 `deepseek-chat`，但 `DeepSeekProvider.id()` 返回 `deepseek-v3`，导致按 0 价格记账
- 解决：定价表 key 改为 `deepseek-v3`

**问题 71：计费按 provider.id() 记 model，无法适应同 provider 多 model 场景**

- 文件：`src/main/java/com/javaclaw/providers/ChatResponse.java:7`、`src/main/java/com/javaclaw/providers/OpenAiCompatibleProvider.java:76`、`src/main/java/com/javaclaw/agent/AgentLoop.java:51`、`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:50`
- 说明：`CostTracker.record()` 的 model 参数来自 `provider.id()`（固定值），若同一 provider 动态切 model 则价格映射失效
- 解决：`ChatResponse` 新增 `model` 字段，`OpenAiCompatibleProvider` 从 API 响应提取实际 model 名，经 `AgentResponse` 穿透到 `DefaultAgentOrchestrator`，`CostTracker` 定价表 key 改回 `deepseek-chat`（API 实际返回值）

---

### Phase 5 Step 3：Agent 集成记忆

**问题 72：Agent 无法主动存取长期记忆**

- 文件：`src/main/java/com/javaclaw/tools/MemoryStoreTool.java`、`src/main/java/com/javaclaw/tools/MemoryRecallTool.java`
- 说明：Agent 缺少工具调用接口来主动存储和检索记忆
- 解决：新增 `memory_store` 和 `memory_recall` 两个 Tool，委托 `MemoryStore` 实现

**问题 73：对话上下文未自动关联记忆**

- 文件：`src/main/java/com/javaclaw/agent/AgentLoop.java:48,69,85`
- 说明：消息进来不会自动召回相关记忆，对话结束不会自动存储
- 解决：`execute()` 入口先 `recall(userMessage, 3)` 注入 `[Recalled memories]` 上下文；两个返回点调用 `storeMemory()` 将 Q&A 摘要写入长期记忆

**问题 74：记忆模块未接入应用入口**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:103`、`src/main/java/com/javaclaw/agent/DefaultAgentOrchestrator.java:38`
- 说明：`LuceneMemoryStore` 未在启动时创建，memory tools 未注册到 ToolRegistry
- 解决：JavaClawApp 创建 `EmbeddingService` + `LuceneMemoryStore`，注册 `MemoryStoreTool`/`MemoryRecallTool`，通过 `agent.setMemoryStore()` 注入 AgentLoop

**问题 75：LuceneMemoryStore 生命周期未回收**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java:112`
- 说明：`LuceneMemoryStore.close()` 未在应用关闭路径调用，长期运行累积文件句柄
- 解决：添加 `Runtime.getRuntime().addShutdownHook()` 调用 `memoryStore::close`

**问题 76：Step3 关键行为缺少测试**

- 文件：`src/test/java/com/javaclaw/tools/MemoryToolsTest.java`、`src/test/java/com/javaclaw/agent/AgentLoopTest.java:107`
- 说明：无 MemoryStoreTool/MemoryRecallTool 专项测试，AgentLoop 未断言 recall/store 生命周期
- 解决：新增 MemoryToolsTest 4 个、AgentLoopTest 增加 memoryRecallAndStoreLifecycle，总测试数 41→46

---

## Phase 6：扩展能力

### Step 1：MCP Client

**问题 77：MCP stdio 帧格式不兼容**

- 文件：`src/main/java/com/javaclaw/mcp/McpClient.java`
- 说明：初版用换行分隔 JSON（newline-delimited），但 MCP 协议规范使用 LSP 风格的 `Content-Length: N\r\n\r\n{body}` 帧格式，主流 MCP Server 均不兼容
- 解决：完全重写 McpClient，实现 Content-Length 帧的读写：`writeMessage()` 写 header+body，`readMessage()` 逐行解析 header 提取长度再读 body

**问题 78：MCP 请求无超时，可永久挂起**

- 文件：`src/main/java/com/javaclaw/mcp/McpClient.java`
- 说明：`sendRequest()` 同步等待响应，若 MCP Server 不回复则线程永久阻塞
- 解决：改为 `ConcurrentHashMap<Integer, CompletableFuture<JsonNode>>` 异步模型，后台线程读响应并 complete 对应 future，`sendRequest()` 用 `future.get(30, SECONDS)` 限时等待，超时抛 IOException

**问题 79：config-example.yaml 缺少 mcp-servers 示例**

- 文件：`config/config-example.yaml`
- 说明：新增 MCP 功能但配置示例文件未更新，用户不知道如何配置
- 解决：追加 mcp-servers 注释示例（filesystem + github）

**问题 80：McpManager 配置解析直接类型转换，非法输入 ClassCastException**

- 文件：`src/main/java/com/javaclaw/mcp/McpManager.java`
- 说明：`(String) cfg.get("command")` 和 `(List<String>) cfg.get("args")` 直接强转，YAML 中非预期类型时抛 ClassCastException
- 解决：加 `instanceof` 检查和 `String.valueOf()` 安全转换，缺少 command 字段时 `log.warn` 跳过

### Step 2：Skill 系统

**问题 81：Skill trigger 匹配与实际消息格式不一致**

- 文件：`src/main/java/com/javaclaw/skills/SkillRouter.java`
- 说明：trigger 用 `startsWith` 匹配，但用户消息可能带前导空格或大小写不同，导致匹配失败
- 解决：匹配前 `strip().toLowerCase()` 标准化

**问题 82：空消息触发 Skill 路由 NPE**

- 文件：`src/main/java/com/javaclaw/skills/SkillRouter.java`
- 说明：`route(null)` 或 `route("")` 未做空保护，直接调 `startsWith` 抛 NPE
- 解决：入口判空，返回 `Optional.empty()`

**问题 83：Skill YAML 解析缺少容错**

- 文件：`src/main/java/com/javaclaw/skills/SkillLoader.java`
- 说明：YAML 文件格式错误时 `Yaml.load()` 抛异常，整个 Skill 系统不可用
- 解决：单个 Skill 文件解析失败时 `log.warn` 跳过，不影响其他 Skill 加载

**问题 84：空 system_prompt 导致 LLM 收到 null content**

- 文件：`src/main/java/com/javaclaw/skills/SkillExecutor.java`
- 说明：Skill 定义中 `system_prompt` 为空时，PromptBuilder 生成的 system message content 为 null，部分 LLM API 拒绝 null content
- 解决：空 system_prompt 时使用默认 prompt 兜底

**问题 85：tools 字段为空时应表示"使用全部工具"**

- 文件：`src/main/java/com/javaclaw/skills/SkillExecutor.java`
- 说明：Skill 定义中 `tools: []` 和 `tools` 字段缺失语义不同——缺失应表示使用全部已注册工具，空列表表示不使用工具。实现时两者都走了空列表分支
- 解决：区分 `tools == null`（全部工具）和 `tools.isEmpty()`（无工具）

**问题 86：Skill 系统缺少测试**

- 文件：`src/test/java/com/javaclaw/skills/`
- 说明：SkillRouter/SkillLoader/SkillExecutor 无测试覆盖
- 解决：新增 SkillRouterTest（trigger 匹配、空消息、优先级）和 SkillLoaderTest（正常加载、容错）

### Step 3：沙箱增强（SandboxConfig）

**问题 87：SandboxConfig timeout 未传递给执行器**

- 文件：`src/main/java/com/javaclaw/shared/config/SandboxConfig.java`、`src/main/java/com/javaclaw/security/DockerExecutor.java`
- 说明：`SandboxConfig.timeoutSeconds()` 已从 YAML 解析，但 `DockerExecutor` 构造时仍硬编码 30s 超时，配置值未生效
- 解决：`DockerExecutor` 构造参数接收 `SandboxConfig`，`execute()` 使用 `config.timeoutSeconds()` 作为超时

**问题 88：网络白名单未按工具粒度隔离**

- 文件：`src/main/java/com/javaclaw/security/DockerExecutor.java`
- 说明：`SandboxConfig.networkWhitelist()` 是全局列表，所有工具共享同一白名单。HttpRequestTool 需要访问 API 域名，但 ShellTool 不应有网络访问权限
- 解决：`DockerExecutor.execute()` 新增 `List<String> networkWhitelist` 参数，由调用方按工具类型传入不同白名单

**问题 89：SandboxConfig 数值字段 String 解析不健壮**

- 文件：`src/main/java/com/javaclaw/shared/config/ConfigLoader.java:81-82`
- 说明：`Integer.parseInt(String.valueOf(...))` 在 YAML 值为浮点数（如 `1.5`）或非数字字符串时抛 NumberFormatException，整个配置加载失败
- 解决：用 `Double.parseDouble()` 后 `(int)` 截断，非法值时 `log.warn` 并使用默认值

### Step 4：工具重构 + 新工具（SecurityPolicy 基础设施）

**问题 90：IPv6 ULA 地址（fc00::/7）未被 SSRF 防护拦截**

- 文件：`src/main/java/com/javaclaw/security/SecurityPolicy.java:169-178`
- 说明：`isPrivateAddress()` 依赖 Java 的 `isSiteLocalAddress()`，但该方法对 `fc00::1` 返回 false（Java 只识别 `fec0::/10`，不覆盖 RFC 4193 的 `fc00::/7`）
- 验证：`jshell` 中 `InetAddress.getByName("fc00::1").isSiteLocalAddress()` 返回 false
- 解决：新增 `isIpv6Ula()` 方法，检查 `(bytes[0] & 0xFE) == 0xFC` 覆盖 fc00::/7 全段

**问题 91：validateDomain 未拒绝 URL userinfo（凭据泄露风险）**

- 文件：`src/main/java/com/javaclaw/security/SecurityPolicy.java:115-167`
- 说明：`https://admin:pass@example.com` 格式的 URL 未被拦截，userinfo 中的凭据可能被发送到目标服务器或记录在日志中
- 解决：`URI.create(url)` 后检查 `getUserInfo() != null`，非空则抛 SecurityException

**问题 92：ActionTracker 竞态条件——size 检查与 addLast 非原子**

- 文件：`src/main/java/com/javaclaw/security/ActionTracker.java:18-26`
- 说明：初版使用 `ConcurrentLinkedDeque`，`deque.size()` 和 `deque.addLast()` 是两个独立操作。并发线程可同时通过 size 检查，导致实际记录数超过限制
- 根因：`ConcurrentLinkedDeque.size()` 是 O(n) 遍历且非原子，check-then-act 模式在并发下不安全
- 解决：改为 `ArrayDeque` + `synchronized(deque)` 块，将 evict + size 检查 + addLast 三步操作原子化

**问题 93：allowed-domains 列表元素类型转换不安全**

- 文件：`src/main/java/com/javaclaw/shared/config/ConfigLoader.java:97-100`
- 说明：`(List<String>) http.get("allowed-domains")` 直接强转，YAML 中若包含非字符串元素（如数字 `123`）则运行时 ClassCastException
- 解决：改为 `((List<?>) ...).stream().map(String::valueOf).collect(Collectors.toSet())`，逐元素安全转换

### Step 5：Provider 可靠性增强

**问题 94：Provider 出现双层重试，导致单次请求被放大**

- 文件：`src/main/java/com/javaclaw/providers/OpenAiCompatibleProvider.java`、`src/main/java/com/javaclaw/providers/ReliableProvider.java`
- 说明：`OpenAiCompatibleProvider.chat()` 与 `ReliableProvider.chat()` 同时包裹重试，导致一次逻辑调用可能触发多倍实际请求，放大延迟与成本
- 解决：移除 `OpenAiCompatibleProvider` 内层重试，仅保留 `ReliableProvider` 作为统一可靠性层

**问题 95：Provider 链路硬编码，忽略配置中的 primary/fallback**

- 文件：`src/main/java/com/javaclaw/gateway/JavaClawApp.java`
- 说明：应用入口直接写死 provider 顺序，`config.yaml` 的 `providers.primary` / `providers.fallback` 实际不生效
- 解决：启动时按配置动态构建 provider 链；未知 provider 或缺少关键配置时给出告警并跳过

**问题 96：ReliableProvider 在 model 为空时触发 NPE**

- 文件：`src/main/java/com/javaclaw/providers/ReliableProvider.java`
- 说明：主链路调用 `ChatRequest(model=null, ...)` 时，`model.equals(...)` 与 `modelFallbacks.get(model)`（`Map.of()` 不接受 null key）会抛 `NullPointerException`
- 解决：使用 `Objects.equals(...)` 比较；`modelChain()` 中对 `null model` 直接跳过 fallback 查询；新增 `ReliableProviderTest.handlesNullModelWithoutNpe`

### 运行时稳定性

**问题 97：CLI 将业务异常误报为 “Read error: null”**

- 文件：`src/main/java/com/javaclaw/channels/CliAdapter.java`
- 说明：`sink.accept(...)` 与 `reader.readLine()` 放在同一 `try`，业务异常被错误标记为输入读取错误，日志误导排查
- 解决：拆分为两段异常处理：输入读取异常输出 `Input read error`，消息处理异常输出 `Message handling error`

### 运维配置

**问题 98：Embedding 模型未安装导致 404，记忆检索退化**

- 文件：`src/main/java/com/javaclaw/memory/EmbeddingService.java`、`src/main/java/com/javaclaw/gateway/JavaClawApp.java`
- 说明：当 `embedding-model` 指向不存在模型（如 `nomic-embed-text` 未 pull）时，嵌入请求返回 404；向量召回失效，仅剩关键词检索路径
- 解决：安装并校验 embedding 模型（如 `ollama pull nomic-embed-text` + `ollama list`），或将配置改为已安装模型

**问题 99：Step 5 规划项 CircuitBreaker / TimeoutBudget 尚未落地**

- 文件：`src/main/java/com/javaclaw/providers/`（缺失 `CircuitBreaker.java`、`TimeoutBudget.java`）
- 说明：执行计划要求 Provider 可靠性包含熔断与总超时预算，但当前实现仅有 `ReliableProvider + ResilientCall`，持续故障场景仍会完整重试链路
- 解决：补齐 `CircuitBreaker`（失败阈值、半开探测、恢复）与 `TimeoutBudget`（总时限共享到重试与降级）

**问题 100：ResilientCall 的 429/Retry-After 关键分支缺少专门测试**

- 文件：`src/test/java/com/javaclaw/providers/ResilientCallTest.java`
- 说明：实现已加入 `isRateLimited()` 与 `parseRetryAfterMs()`，但当前测试主要覆盖通用重试与指数退避，缺少 429 + Retry-After 行为断言
- 解决：补充用例：`429 + retry-after` 正确等待、`retry-after` 非法值回退默认退避、非 429 不走该分支
