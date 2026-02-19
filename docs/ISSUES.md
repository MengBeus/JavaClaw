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

### 环境配置

**问题 6：PATH 中 `%JAVA_HOME%` 未展开**

- 说明：通过 PowerShell 写入 PATH 时用了 `%JAVA_HOME%\bin`，PowerShell 不解析 `%` 语法，导致路径无效
- 错误：`java : The term 'java' is not recognized as the name of a cmdlet`
- 解决：改为写入完整路径 `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin`

**问题 7：JAVA_HOME\bin 未加入 PATH**

- 说明：初次配置时只设了 `JAVA_HOME` 变量和 Maven 路径，漏了 `JAVA_HOME\bin`
- 错误：Maven 报 `The JAVA_HOME environment variable is not defined correctly`
- 解决：将 JDK bin 目录追加到用户 PATH
