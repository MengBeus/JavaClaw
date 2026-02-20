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
