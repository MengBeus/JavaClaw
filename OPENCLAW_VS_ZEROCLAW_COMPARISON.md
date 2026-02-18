# OpenClaw vs ZeroClaw 架构对比分析

> 目的：为 JAVAClaw 的架构设计提供参考

---

## 1. 总体定位对比

| 维度 | OpenClaw (TypeScript) | ZeroClaw (Rust) |
|------|----------------------|-----------------|
| 定位 | 功能丰富的全能助手 | 极致轻量的嵌入式助手 |
| 语言 | TypeScript / Node.js ≥22 | Rust（100% 单二进制） |
| 包管理 | pnpm monorepo | Cargo 单 crate + 1 子 crate |
| 产物 | npm 全局包 + 多进程 | 单二进制文件 |
| 内存 | 较高（Node.js 运行时开销） | ~3.9MB 峰值 |
| 冷启动 | 秒级 | <10ms |
| 目标硬件 | 桌面/服务器 | $10 开发板到服务器 |
| 扩展方式 | npm 插件（运行时加载） | Trait（编译时替换） |
| 代码规模 | 大（src/ 下 47 个子目录） | 中（src/ 下 27 个子目录） |

---

## 2. Gateway 网关对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 规模 | 重量级（160+ 文件） | 极简（单文件 `mod.rs`） |
| 协议 | WebSocket + HTTP（兼容 OpenAI API） | WebSocket |
| 绑定 | `ws://127.0.0.1:18789` | `127.0.0.1`，拒绝 `0.0.0.0` |
| 职责 | 控制平面核心：路由、会话、配置、事件、RPC | 轻量路由：认证 + 转发 |
| 配置热重载 | 有（`config-reload.ts`） | 无（编译时确定） |
| OpenAI 兼容 | 有（`openai-http.ts`） | 无 |

**关键差异**：OpenClaw 的 Gateway 是"大脑"，几乎所有逻辑都经过它；ZeroClaw 的 Gateway 只是薄薄一层路由，真正的逻辑在 Agent Loop 里。

---

## 3. Agent 运行时对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 名称 | Pi Agent Runtime | Agent Loop |
| 模式 | RPC 模式，独立进程 | 内嵌主循环，同进程 |
| 流式 | tool streaming + block streaming | 直接流式 |
| 意图分类 | 通过路由规则 | 专用 `classifier.rs` |
| 记忆加载 | 插件槽位 | 内置 `memory_loader.rs` |
| 多 Agent | 多 Agent 路由到隔离工作区 | 单 Agent（通过 `delegate` 工具委派） |
| 提示词 | Gateway 内构建 | Agent 内 `prompt.rs` 构建 |

**关键差异**：OpenClaw 的 Agent 是独立 RPC 进程，Gateway 负责编排；ZeroClaw 的 Agent 是主循环内嵌，自己管理整个生命周期。

---

## 4. Channels 消息通道对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 数量 | 12+ 平台 | 16 平台 |
| 代码组织 | 每个平台独立目录（`src/whatsapp/`等） | 全部平铺在 `src/channels/` 下，每平台一个 `.rs` |
| 抽象层 | `registry.ts` + `session.ts` + gating | `traits.rs` 定义 Channel trait |
| 中国平台 | 无 | 有（QQ、钉钉、飞书） |
| 邮件 | 无 | 有（`email_channel.rs`） |
| IRC | 无 | 有（`irc.rs`） |

**OpenClaw 独有**：
- 通道级插件系统（`channels/plugins/`）
- 白名单子目录（`channels/allowlists/`）
- 命令门控 / @提及门控

**ZeroClaw 独有**：
- 更多中国本土平台（QQ、钉钉、飞书）
- 邮件和 IRC 支持

---

## 5. Providers AI 后端对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 抽象方式 | `src/providers/` 目录，具体实现不详 | `traits.rs` 定义 Provider trait |
| 数量 | 未明确列出 | 28+（通过 `compatible.rs` 覆盖大量 OpenAI 兼容商） |
| 路由 | 单模型 | `router.rs` 多模型路由 |
| 可靠性 | 未明确 | `reliable.rs` 内置重试/降级 |
| 中国模型 | 未明确 | 有（`glm.rs` 智谱） |
| Ollama | 未明确 | 有（`ollama.rs` 本地模型） |

**ZeroClaw 的亮点**：`compatible.rs` 一个文件就覆盖了所有 OpenAI 兼容协议的提供商，非常聪明的设计。

---

## 6. Tools 工具对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 组织方式 | 分散在多个独立目录 | 集中在 `src/tools/` 下，每工具一个 `.rs` |
| 数量 | 10+ 类别 | 30 个工具文件 |
| 抽象 | 各模块独立实现 | `traits.rs` + `schema.rs` 统一抽象 |

**OpenClaw 独有工具**：
- Canvas / A2UI 可视化工作区
- TTS 文字转语音
- 媒体理解（图片/音频/视频）
- 链接内容理解
- Webhooks

**ZeroClaw 独有工具**：
- 硬件交互（`hardware_board_info`、`hardware_memory_map`、`hardware_memory_read`）
- Git 操作（`git_operations.rs`）
- 代理配置（`proxy_config.rs`）
- Pushover 通知（`pushover.rs`）
- Composio 集成（`composio.rs`）

**关键差异**：OpenClaw 的工具偏"富媒体"（Canvas、TTS、媒体理解）；ZeroClaw 的工具偏"系统级"（硬件、Git、代理）。

---

## 7. Memory 记忆系统对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 设计 | 插件槽位，同时只运行一个实现 | 自研全栈搜索引擎，内置多后端 |
| 存储后端 | 由插件决定 | SQLite / PostgreSQL / Markdown / 无 |
| 向量搜索 | 未明确 | SQLite BLOB + 余弦相似度 |
| 关键词搜索 | 未明确 | FTS5 + BM25 |
| 混合搜索 | 未明确 | `lucid.rs` 向量 + 关键词加权合并 |
| 嵌入生成 | 未明确 | OpenAI / 自定义 URL / noop |
| 缓存 | 未明确 | `response_cache.rs` 响应缓存 |
| 外部依赖 | 取决于插件 | 零外部依赖 |

**关键差异**：ZeroClaw 在记忆系统上投入最大，自研了完整的向量+关键词混合搜索引擎，不依赖任何外部服务。OpenClaw 把记忆当插件处理，更灵活但深度不够。

---

## 8. Security 安全对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 配对认证 | DM 配对码 | 6 位一次性码 + Bearer Token |
| 沙箱 | 群聊 session 可沙箱 | 4 种沙箱：Bubblewrap/Firejail/Landlock/Docker |
| 沙箱检测 | 无 | 自动检测可用沙箱（`detect.rs`） |
| 文件系统 | 未明确限制 | 14 个系统目录 + 4 个 dotfile 屏蔽 |
| Symlink | 未明确 | 逃逸检测（canonicalization） |
| 白名单 | 有（`allowlists/`） | 默认拒绝，显式 opt-in |
| MCP | mcporter 桥接 | 无 MCP |
| 审计 | 未明确 | `audit.rs` 审计日志 |
| 密钥管理 | 未明确 | `secrets.rs` |

**关键差异**：ZeroClaw 的安全做得更深——4 种沙箱自动检测、文件系统作用域、symlink 逃逸检测。OpenClaw 更依赖"信任主 session"的模型。

---

## 9. Runtime 运行时对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 执行环境 | Native + Docker | Native + Docker + WASM |
| 进程模型 | 多进程（Gateway + Agent RPC） | 单进程 |
| 守护进程 | `openclaw onboard --install-daemon` | `src/daemon/` |
| 诊断 | 未明确 | `src/doctor/` 诊断工具 |
| 健康检查 | 未明确 | `health/` + `heartbeat/` |
| 成本追踪 | 未明确 | `cost/` 模块 |

---

## 10. 客户端对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| CLI | `src/cli/` + `src/commands/` | 内置（`src/channels/cli.rs`） |
| TUI | `src/tui/` + `src/terminal/` | 无 |
| WebChat | `ui/` 独立前端 | 无 |
| macOS | `apps/macos/` 原生 app | 无 |
| iOS | `apps/ios/` | 无 |
| Android | `apps/android/` | 无 |
| Python 绑定 | 无 | `python/` |

**关键差异**：OpenClaw 有完整的多端客户端生态；ZeroClaw 专注后端，CLI 就是唯一的本地客户端，但提供了 Python 绑定用于集成。

---

## 11. 插件/扩展系统对比

| 维度 | OpenClaw | ZeroClaw |
|------|----------|----------|
| 扩展方式 | npm 插件（运行时动态加载） | Trait（编译时静态替换） |
| 插件 SDK | `src/plugin-sdk/` | 无（直接实现 trait） |
| 插件加载 | `src/plugins/` 运行时扫描 | 编译时确定 |
| 技能系统 | ClawHub 技能注册中心 | `skillforge/` + `skills/` |
| MCP | mcporter 桥接，可热插拔 | 无 MCP |
| 第三方集成 | `extensions/` | `src/integrations/` |

**关键差异**：OpenClaw 走"运行时插件"路线，灵活但有安全和稳定性风险；ZeroClaw 走"编译时 trait"路线，安全稳定但不够灵活。

---

## 12. 架构哲学总结

| | OpenClaw | ZeroClaw |
|---|----------|----------|
| 一句话 | "功能全、生态广、可 hack" | "极致小、极致快、极致安全" |
| 复杂度来源 | 多端客户端 + 富媒体工具 + 插件生态 | 多沙箱 + 自研搜索引擎 + 硬件支持 |
| 适合谁 | 想要全功能 AI 助手的桌面用户 | 想要轻量部署的开发者/嵌入式场景 |
| 代码风格 | 大目录、多文件、职责分散 | 扁平结构、每模块一个文件、职责集中 |

---

## 13. 对 JAVAClaw 的启示

### 从 OpenClaw 学什么

1. **Gateway 作为控制平面**的思路值得借鉴——你的 V1 架构已经采用了这个方案，方向正确
2. **OpenAI 兼容 HTTP 端点**很实用，让第三方工具可以直接对接
3. **多端客户端生态**是长期价值，但 MVP 阶段不需要
4. **MCP 桥接而非内置**的策略很聪明，降低核心复杂度

### 从 ZeroClaw 学什么

1. **`compatible.rs` 模式**——用一个 OpenAI 兼容实现覆盖大量 Provider，Java 版可以做 `OpenAiCompatibleProvider`
2. **自研混合搜索引擎**——向量 + FTS 混合搜索，Java 可用 Lucene 实现，比 ZeroClaw 的 SQLite 方案更强
3. **4 种沙箱 + 自动检测**——Java 可用 Docker + ProcessBuilder 权限限制 + SecurityManager（虽已废弃但思路可参考）
4. **`reliable.rs` 重试/降级**——Provider 层内置可靠性，你的 V1 没提到这个，建议加上
5. **`cost/` 成本追踪**——LLM 调用很贵，内置成本追踪很实用
6. **`doctor/` 诊断工具**——自检能力，对运维很有帮助

### 你的 V1 架构已经做对的事

1. **模块化单体**——比 OpenClaw 的 monorepo 更适合 Java，比 ZeroClaw 的单 crate 更有结构
2. **Gateway + Agent + Session + Channel 分层**——和两者的核心思路一致
3. **接口先行**（Tool/Channel/Provider 都定义了接口）——对应 ZeroClaw 的 trait-driven
4. **审批流程**——`ApprovalService` 对应 ZeroClaw 的 `approval/`
5. **幂等设计**——两个原版都没明确提到，你的 V1 反而更严谨

### 你的 V1 可以补充的

1. **Provider 可靠性层**——重试、降级、熔断（参考 ZeroClaw 的 `reliable.rs`）
2. **OpenAI 兼容 Provider**——一个实现覆盖大量模型商（参考 ZeroClaw 的 `compatible.rs`）
3. **成本追踪模块**——记录每次 LLM 调用的 token 消耗和费用
4. **诊断工具**——`/doctor` 命令自检环境、连接、配置
5. **意图分类器**——在 Agent 层加一个 Classifier，决定是否需要调 LLM（参考 ZeroClaw）
6. **记忆系统深度**——不只是存取，要有向量搜索 + 关键词搜索的混合检索（Java 用 Lucene 天然优势）
7. **沙箱执行**——工具执行的隔离策略，至少支持 Docker 沙箱

---

## 14. JAVAClaw 的 Java 生态优势

这些是 TypeScript 和 Rust 都不容易做到，但 Java 天然擅长的：

| 能力 | Java 生态方案 |
|------|--------------|
| 混合搜索引擎 | Lucene（比 ZeroClaw 的 SQLite FTS5 强得多） |
| 运行时插件 | SPI / Spring Plugin / OSGi（比 npm 插件更安全） |
| 沙箱执行 | Docker SDK for Java + ProcessBuilder 权限控制 |
| 可观测性 | Micrometer + OpenTelemetry（你 V1 已规划） |
| 定时任务 | Quartz / Spring Scheduler（成熟方案） |
| 流式处理 | Project Reactor / Virtual Threads（Java 21） |
| 多模型路由 | Spring Cloud Gateway 模式复用 |
| 热重载配置 | Spring Cloud Config / @RefreshScope |

---

## 15. 总结：三条路线

```
OpenClaw (TS)  ──  "大而全"，功能丰富，多端客户端，插件生态
ZeroClaw (Rust) ── "小而美"，极致轻量，编译时确定，嵌入式友好
JAVAClaw (Java) ── "稳而强"，企业级基础设施，运行时可扩展，生态成熟
```

JAVAClaw 不需要复制任何一个，而是取两者之长：
- 学 OpenClaw 的 **Gateway 控制平面 + 多端扩展思路**
- 学 ZeroClaw 的 **接口驱动 + 混合搜索 + 可靠性 + 成本追踪**
- 发挥 Java 的 **Lucene + SPI + Virtual Threads + Spring 生态**优势
