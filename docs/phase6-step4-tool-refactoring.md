# Phase 6 Step 4: Tool Refactoring + New Tools (ZeroClaw Pattern)

## Context
JAVAClaw现有5个工具缺少统一安全层。参照ZeroClaw的设计，引入SecurityPolicy（rate limiting + path sandbox + domain allowlist + env sanitization），重构现有工具并新增4个工具。

## 分10个commit，按依赖顺序执行

---

### Commit 1: SecurityPolicy 基础设施

**新建文件：**

- `src/main/java/com/javaclaw/security/ActionTracker.java`
  - 1小时滑动窗口 rate limiter
  - `ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>>`
  - `track(toolName)` 超限抛 SecurityException

- `src/main/java/com/javaclaw/security/CommandRisk.java`
  - 枚举: LOW / MEDIUM / HIGH

- `src/main/java/com/javaclaw/security/SecurityPolicy.java`
  - 构造: workspaceRoot, workspaceOnly, maxActionsPerHour, allowedDomains
  - `checkRateLimit(toolName)` — 委托 ActionTracker
  - `validatePath(rawPath, checkSize)` — 5层: 策略检查→canonicalize→escape检查→symlink检查→大小检查(10MB)
  - `validateDomain(url)` — 完整SSRF防护链:
    1. scheme验证（仅http/https）
    2. host提取（拒绝userinfo、处理IPv6方括号）
    3. DNS解析后校验：InetAddress.getByName() 拿到真实IP，再检查私网范围
    4. 私网IP全覆盖：127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10(CGN), ::1, fe80::/10, fc00::/7, ::ffff:映射IPv4
    5. localhost变体阻断：localhost, *.localhost, *.local, 0.0.0.0, [::]
    6. allowlist精确匹配+子域匹配
  - HttpClient构造时设 `followRedirects(NEVER)`，禁止自动跟随重定向（防重定向链绕过）
  - `classifyCommand(command)` → CommandRisk
  - `sanitizedEnv()` — 按 OS 最小保留变量集:
    - Unix: PATH, HOME, TERM, LANG, USER, SHELL, TMPDIR
    - Windows: PATH, SystemRoot, ComSpec, TEMP, TMP, USERPROFILE, HOMEDRIVE, HOMEPATH

---

### Commit 2: ToolsConfig 配置扩展

**新建：**
- `src/main/java/com/javaclaw/shared/config/ToolsConfig.java`
  - 内嵌 record: HttpRequestConfig(enabled, allowedDomains, timeout, maxResponseSize)
  - 内嵌 record: WebSearchConfig(enabled, provider, maxResults, timeout)
  - 内嵌 record: SecurityConfig(maxActionsPerHour, workspaceOnly)

**修改：**
- `src/main/java/com/javaclaw/shared/config/JavaClawConfig.java` — 加 `ToolsConfig tools` 字段
- `src/main/java/com/javaclaw/shared/config/ConfigLoader.java` — 解析 `tools:` YAML section
- `config/config-example.yaml` — 加 tools 配置示例

```yaml
tools:
  http-request:
    enabled: true
    allowed-domains: ["api.github.com"]
    timeout: 30
    max-response-size: 1048576
  web-search:
    enabled: true
    provider: duckduckgo
    max-results: 5
    timeout: 15
  security:
    max-actions-per-hour: 120
    workspace-only: true
```

---

### Commit 3: 重构现有5个工具

**修改：**
- `FileReadTool.java` — 加 SecurityPolicy，checkRateLimit + validatePath 替代手动sandbox
- `FileWriteTool.java` — 加 SecurityPolicy，checkRateLimit + **用 securityPolicy.validatePath(path, false) 替代手动sandbox**，与 FileReadTool 统一策略入口（删除原有5层手写逻辑）
- `ShellTool.java` — 加 SecurityPolicy，checkRateLimit + classifyCommand日志 + **调用 securityPolicy.sanitizedEnv() 传给 executor**
- `RestrictedNativeExecutor.java` — execute() 接受 env Map，用 `pb.environment().clear(); pb.environment().putAll(env)` 应用
- `DockerExecutor.java` — execute() 接受 env Map，通过 `docker run -e` 注入（仅白名单变量）
- `ToolExecutor.java` — 接口加 `default ExecutionResult execute(cmd, workDir, timeout, toolName, Map<String,String> env)` 重载
- `MemoryStoreTool.java` — 加 SecurityPolicy + checkRateLimit
- `MemoryRecallTool.java` — 加 SecurityPolicy + checkRateLimit
- `ShellToolTest.java` — 更新构造函数

---

### Commit 4: MemoryForgetTool

**新建：**
- `src/main/java/com/javaclaw/tools/MemoryForgetTool.java`
  - input: `{memory_id: string}`
  - 委托 `memoryStore.forget(id)` + checkRateLimit

---

### Commit 5: GitTool

**新建：**
- `src/main/java/com/javaclaw/tools/GitTool.java`
  - input: `{operation: string, args: string}`
  - 支持: status, diff, log, show, branch, add, commit
  - 安全: 参数sanitize（拒绝 ; | & $ ` > \n），ProcessBuilder直接调用（非shell）
  - @DangerousOperation + checkRateLimit

---

### Commit 6: HttpRequestTool

**新建：**
- `src/main/java/com/javaclaw/tools/HttpRequestTool.java`
  - input: `{url, method, headers, body}`
  - 方法白名单: GET/POST/PUT/DELETE/PATCH/HEAD
  - SSRF防护: securityPolicy.validateDomain(url)
  - java.net.http.HttpClient（无新依赖）
  - 响应截断 + 敏感header脱敏 + checkRateLimit

---

### Commit 7: WebSearchTool

**新建：**
- `src/main/java/com/javaclaw/tools/WebSearchTool.java`
  - input: `{query: string}`
  - DuckDuckGo HTML scraping（html.duckduckgo.com/html/?q=...）
  - 正则提取结果 + 限制 maxResults + checkRateLimit

---

### Commit 8: JavaClawApp 接线

**修改：**
- `src/main/java/com/javaclaw/gateway/JavaClawApp.java`
  - 构造 SecurityPolicy（从 config.tools().security()）
  - 所有工具构造传入 SecurityPolicy
  - 注册 GitTool, HttpRequestTool(条件), WebSearchTool(条件), MemoryForgetTool

---

### Commit 9: SlackAdapter

**新建：**
- `src/main/java/com/javaclaw/channels/SlackAdapter.java`
  - 实现 ChannelAdapter 接口
  - Slack Bolt SDK（Socket Mode），接收消息 → InboundMessage，发送 → OutboundMessage
  - 支持 thread reply（channelId 编码 channel:ts）

**修改：**
- `pom.xml` — 加 slack-bolt 依赖
- `src/main/java/com/javaclaw/shared/config/JavaClawConfig.java` — 加 slackBotToken, slackAppToken
- `src/main/java/com/javaclaw/shared/config/ConfigLoader.java` — 解析 slack section
- `src/main/java/com/javaclaw/gateway/JavaClawApp.java` — 条件注册 SlackAdapter + SlackApprovalStrategy
- `src/main/java/com/javaclaw/approval/SlackApprovalStrategy.java` — Slack 通道审批（类似 TelegramApprovalStrategy）
- `config/config-example.yaml` — 加 slack 配置示例

---

### Commit 10: BrowserTool（可选，基础版）

**新建：**
- `src/main/java/com/javaclaw/tools/BrowserTool.java`
  - input: `{url: string, action: string}`
  - action 支持: navigate（获取页面内容）、screenshot（截图）
  - HTTPS only + domain allowlist（复用 securityPolicy.validateDomain）
  - 实现: Playwright（com.microsoft.playwright），headless Chromium
  - @DangerousOperation + checkRateLimit

**修改：**
- `pom.xml` — 加 `com.microsoft.playwright:playwright` 依赖

**修改：**
- `config/config-example.yaml` — 加 browser 配置（enabled, allowed-domains）
- `src/main/java/com/javaclaw/shared/config/ToolsConfig.java` — 加 BrowserConfig record
- `src/main/java/com/javaclaw/gateway/JavaClawApp.java` — 条件注册 BrowserTool

---

## 验证

### 每个 Commit 必须通过 `mvn compile` + `mvn test`

### 单元测试要求（随对应 Commit 一起提交）

| Commit | 测试文件 | 覆盖点 |
|--------|----------|--------|
| 1 | `ActionTrackerTest.java` | 窗口内计数、超限抛异常、过期自动清除 |
| 1 | `SecurityPolicyTest.java` | validatePath: 正常路径/traversal/symlink/超大文件; validateDomain: 合法域名/私网IP/IPv6/localhost变体/非allowlist; classifyCommand: LOW/MEDIUM/HIGH分类; sanitizedEnv: 只含白名单变量 |
| 2 | `ToolsConfigTest.java` | ConfigLoader 解析 tools section: 完整配置/缺省默认值/部分字段缺失; allowedDomains 类型转换 |
| 3 | `ShellToolTest.java` | 更新构造函数，验证 rate limit 触发后返回 isError |
| 4 | `MemoryForgetToolTest.java` | 正常删除返回成功; 空 memory_id 返回 error; memoryStore 异常时返回 error |
| 5 | `GitToolTest.java` | 正常 status/diff; 拒绝含 ; \| & $ ` 的 args; 拒绝非法 operation |
| 6 | `HttpRequestToolTest.java` | SSRF: 拒绝 127.0.0.1/10.x/::1/localhost; allowlist: 拒绝非白名单域名; 方法白名单: 拒绝 TRACE |
| 7 | `WebSearchToolTest.java` | 空 query 返回 error; 正常 query 返回格式化结果（mock HTTP） |
| 9 | `SlackAdapterTest.java` | 消息收发: InboundMessage 构造正确; OutboundMessage 发送调用 Slack API; thread reply channelId 编码/解码 |
| 9 | `SlackApprovalStrategyTest.java` | 审批消息发送; 用户确认→返回true; 用户拒绝→返回false; 超时→返回false |

### 集成验证（Commit 8 完成后）
- 全量 `mvn test` 通过
- 手动: 配置 allowed-domains 后 http_request 可访问白名单域名，非白名单被拒绝
- 手动: web_search 返回 DuckDuckGo 结果
- 手动: git status/diff 正常，git 带 shell 元字符的 args 被拒绝

### 集成验证（Commit 9 完成后）
- 配置 slack bot-token + app-token 后 SlackAdapter 启动无异常
- Slack 发消息 → CLI 收到 InboundMessage; agent 回复 → Slack 收到 OutboundMessage
- 无 Docker 时 SlackApprovalStrategy 发送审批消息，用户点确认/拒绝正确返回

### 集成验证（Commit 10 完成后）
- 配置 browser.enabled + allowed-domains 后 BrowserTool 注册成功
- navigate action 返回页面文本内容; screenshot action 返回截图路径
- 非 allowlist 域名被 validateDomain 拒绝
