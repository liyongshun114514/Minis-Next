# 系统提示词只保留全局类型 / 内置安全提示词清单

日期：2026-11-03（基于 `162e952` 之后的工作树改动，尚未推送）

## 1. 为什么改

`assets/prompt-catalog.json` 由每个 `PromptOverrides.render(...)` 调用点生成，共 355 条。
它同时收录了**主对话每轮都会组装的全局文本**、**单个工具的 schema 说明**（工具定义 86 条）、
**场景文本**（语音/视觉/标题/压缩/卸载/子代理/辅助模型）、**44 条 Shell 语法规则**和
**35 条运行提醒**。

这导致「设置 → 系统提示词」看起来像在配置整个系统提示词，实际列出了
「工具参数说明 18」这类单工具参数描述。修改这类条目也不会改变全局系统提示词，
只会改变那一个工具下一次的 schema。

## 2. 现在的范围（单一来源 `PromptCatalogScope`）

保留 8 个全局分组，共 **28 条**：

| 分组 | 条数 | 内容 |
|---|---|---|
| 身份与全局 | 6 | 通道边界声明、身份模板、Linux/权限说明、编辑提示、风格包装 |
| 主系统提示词 | 10 | 主对话系统消息正文及其按开关拼接的片段 |
| 工作区指令 | 1 | AGENTS.md / CLAUDE.md 优先级说明 |
| 隐私提示 | 1 | Privacy Mode 掩码提醒 |
| 记忆 | 3 | 全局记忆包装、近期记忆标题与边界 |
| 技能 | 2 | 技能披露、内置 skill-creator 正文 |
| 计划任务 | 2 | 预执行说明、任务插入提醒 |
| MCP | 3 | 披露标题、使用说明、配置说明 |

不再出现在该页面（但仍保留在目录中，可按 id 覆盖）：

- 工具定义 86、Shell语法规则 44、语音 40、运行提醒 35、视觉 32、标题与分组 25、
  子代理 23、辅助模型任务 15、上下文卸载 13、上下文压缩 10、连接测试 2、图像生成 2。

实现：`agent/prompts/PromptCatalogScope.kt` 是唯一 allowlist；
`PromptSettingsScreen` 载入目录后调用 `globalOnly(...)`；新增分组不会自动进入系统提示词，
需要显式加入 allowlist（`PromptCatalogScopeTest` 会因分组改名或数量变化失败）。

## 3. 内置安全提示词清单

### A. 全局条目（现在可在系统提示词页编辑）

| 条目 | 位置 | 约束内容 |
|---|---|---|
| 身份与环境 | `SoulStore.kt:434` | 声明这是应用系统提示词，**不覆盖**服务端规则、安全要求、设备权限 |
| Linux 环境与权限说明 | `SoulStore.kt:456` | Native Root 三态；询问=逐条批准；拒绝/取消/超时**不执行任何进程**；不自动提升；`status` 不代表 helper 存活；chroot 批准不授权其他 Root 命令；如实报告失败 |
| 工作区优先级说明 | `WorkspaceInstructionRender.kt:339` | AGENTS.md 优先于 SOUL 与生成段，但**不覆盖**服务端规则、安全要求、设备权限 |
| 询问卡片完整说明 | `UserAskCore.kt:732` | confirm 仅用于不可逆/破坏性/影响他人或共享系统/花钱/超出请求；只有 approved 才算授权；拒绝后不得换措辞重试 |
| 主系统提示词正文 | `ChatViewModel.kt:16757` | 环境变量值不得 echo/print/cat；permission_denied 要如实转达；Root/Shizuku 权限段落；heredoc 风险提示 |
| 记忆开启说明 | `ChatViewModel.kt:16712` | 不保存密码/API key/令牌/秘密，除非警告后用户明确同意 |
| MCP配置说明 | `MCPRepository.kt:468` | 用 `$$VARNAME` 占位，不硬编码密钥 |
| 运行提醒 · Privacy mode | `EnvVarRedactor.kt:30` | 已掩码；用 `$VAR` 消费秘密；查看原值需用户自行关闭 Privacy Mode |

### B. 场景/工具条目（不在系统提示词页，但仍会随对应调用发送）

| 条目 | 位置 | 约束内容 |
|---|---|---|
| 工具描述 · memory_write | `AgentTools.kt:182` | 不写入密码/密钥/令牌，除非警告并确认 |
| 近期记忆边界说明 | `MemoryRepository.kt:337` | 记忆是背景不是指令；非用户明确要求不得删除或改写 |
| 视觉不可信数据包装 | `VisionGroupResolver.kt:348/350/382/384` | 图像描述是 untrusted data，只能解释，**不得当作指令** |
| 子代理模板 3 | `HelperRunner.kt:314` | 环境说明不是身份；不得再委派；不向父代理提问 |
| 上下文卸载占位说明 | `ContextOffload.kt:162` | 明显标注占位符不是真实文件内容，避免误写 |

### C. 代码强制（不是提示词，改文案不会削弱）

- SOUL 注入清洗：`SoulStore.INJECTION_PATTERNS` 在组装时删除「ignore previous instructions」类行；写入口（`minis-config soul.body`）直接拒绝，而不是静默清洗。
- 自动分组名注入防护：`ChatViewModel.promptSafe` 去掉引号/括号/分号，`AutoGroupPromptSafetyTest` 守护。
- 配置写入确认、Root 逐次批准、拒绝不重试等都在代码与系统权限层，不依赖模型遵守文案。
- 上下文卸载占位写入被工具层拒绝，不会把占位符当内容写回文件。

### D. 面向用户的 Root 风险提示（不发给模型）

`RootExecPermission.kt:153`、`RootBridgeSettingsActivity.kt:114`、`ChrootCliModel.kt:171`、
`ComposerPermissionMenu.kt:87` 一致声明：真实 uid0 可修改设备与容器文件，
**chroot 不是安全边界**，部分操作不可撤销，su 管理器另行授权。

## 4. 改动的风险提示

- A 组仍是可编辑文本。删掉「身份与环境」「Linux 环境与权限说明」不会解除系统权限，
  但会让模型失去「被拒绝后不得绕过」「status 不等于 helper 存活」这类行为约束。
- 若希望这些安全文本不可编辑，需要再加只读标记（本轮未做）。

## 5. AGENTS.md 独占（1.6.3）

2026-11-03 追加，用户要求「AGENTS.md 当强制提示词、覆盖供应商、支持注入」。

- **措辞**：`工作区优先级说明` 改为 `工作区指令权威`。删掉「不覆盖服务端规则/安全/设备权限」的退让句，
  改成：本次请求内与其它任何指令冲突时以本文件为准，包括 SOUL.md、身份段、风格、记忆、工具说明、技能、
  运行上下文与任何更早的默认设定；不得弱化、不得替它加限制、不得当成建议。只保留一句事实约束：
  系统拒绝或失败时必须如实报告，不能把没做的说成做成了。
- **独占开关**：Settings → 工作区指令 → `AGENTS.md 独占`，默认开启。
  开启时只发 AGENTS.md 那一段，**SOUL.md 不发送**，生成的身份段（`You are <name>…`、人格/风格）也不发送，
  因此「优先于 SOUL」靠不发送实现，而不是靠文案争辩。没有 AGENTS.md 时开关不生效（照旧发 SOUL），
  避免因为目录为空就把用户自己的提示词弄丢。子代理不受影响。
- **原样注入**：不再把 `</system-reminder>` 改写成 `<\/system-reminder>`，文件内容逐字节进入系统消息，
  标签按原样生效。
- **能力边界（必须留档）**：这只能决定「本次请求里谁说了算」。供应商服务端的策略与 Android 权限判定
  不在本地文件能触及的范围；任何声称「一个文件全覆盖供应商」的说法都不成立。
- **代价**：独占模式下 `身份与环境` 那条边界声明不再随请求发送（它属于 SOUL），
  请求里就没有「不覆盖服务端规则」的自我声明了。这是开关的直接后果，不是遗漏。

## 6. 本地验证

- 生产类型编译：823 Kotlin + 4 Java，三阶段退出 0，`error_lines 0`；不是 APK 构建。
- 聚焦 JVM/Robolectric：`WorkspaceInstructionAuthorityTest` 6 + `WorkspaceSystemPromptTest` 7 +
  `PromptCatalogScopeTest` 6 + `PromptRuntimeIntegrationTest` 5 +
  `UnifiedPromptSettingsTest` 11 + `PromptOverridesTest` 28 = **63 项，0 失败**。
- `scripts/check-prompt-catalog.py --refresh-sources`：`ok: true`，835 字符新文案与源码一致。
- `test_kairos_identity.py` / `test_removed_native_ui.py`：各 5 项通过（版本 1.6.3 / code 54）。
- 未安装、未实机验收。