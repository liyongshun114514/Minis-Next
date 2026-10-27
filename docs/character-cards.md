# 角色卡（Character Cards）

OpenMinis Android 的持久化与运行时身份已从 `SOUL.md` / `SOUL_PROMPT_RULES.json`
整体迁移到**角色卡**。运行时只读写角色卡；旧文件只在首次迁移时读取一次，之后归档为
非活跃兼容备份，不再生成、不再双写。

- 存储：`<filesDir>/minis-global/memory/CHARACTER_CARDS.json`
- 头像：卡片 `icon` 字段（emoji，或 `data:image/png;base64,…`）
- 旧文件归档：`<filesDir>/minis-global/memory/legacy-soul-archive/`
- 备份：`BackupExporter` 逐文件遍历 memory 目录，因此卡文档与归档目录自动随备份走，
  无需改备份代码

## 数据模型（固定 API）

```kotlin
enum class CharacterCardScope { GLOBAL, GROUP, MODEL }

data class CharacterCard(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val icon: String = "",                  // emoji 或 data:image/png;base64,…
    val enabled: Boolean = true,
    val scope: CharacterCardScope = GLOBAL,
    val groupId: String? = null,
    val providerInstanceId: String? = null,
    val modelId: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val messageExamples: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val style: String = "",
    val lang: String = "auto",
    val legacyEmoji: String = "",
    val sourceJson: String = "",
    val legacyRuleDerived: Boolean = false, // 追加字段，仅旧规则视图使用
)
```

`CharacterCardStore`（`agent/CharacterCardStore.kt`）：

| API | 语义 |
| --- | --- |
| `load(context): List<CharacterCard>` | 永不抛异常；缺失 = 空列表；损坏 = 空列表 + 已记录 issue，**原文件不动** |
| `loadReporting(context): CharacterCardLoad` | 带 `issue` 的读取，供 UI 展示 |
| `save(context, cards)` | 原子写 + 回读校验；已有文档损坏时抛 `IllegalStateException`，绝不覆盖 |
| `invalidate()` | 清缓存（备份恢复后调用） |
| `importCard(bytes): CharacterCard` | Tavern V1/V2/V3 JSON 或角色卡 PNG |
| `exportCard(card): String` | 合并式导出（见下） |
| `fileLocation(context): File` | 卡文档位置 |
| `primaryGlobal(cards)` / `primaryGlobal(context)` | 第一个 enabled 的 GLOBAL 卡（身份卡） |
| `editableGlobal(cards)` | 第一个 GLOBAL 卡（含 disabled），设置写入目标 |
| `writeChecked(file, cards)` / `writeVerified(file, cards)` / `readStrict(file)` | 无 Context 的文件级原语，供测试与迁移使用 |
| `importLegacy(context)` / `mergeLegacyFiles(context)` | 旧文件合并 + 归档，返回 `LegacyMigrationReport` |
| `quarantineCorrupt(context)` | 显式把损坏文档移开，供用户导出/修复 |

纯函数（同文件 / `agent/CharacterCardPrompt.kt`）：

- `CharacterCard.promptContent()` / `withPromptContent(text)` — 单卡 → 旧单字段映射：
  GLOBAL 映射 `personality`，scoped 映射 `systemPrompt`，其余字段原样保留。
- `CharacterCardPrompt.resolve(cards, selection): CharacterCardChain`
- `CharacterCardPrompt.globalBody(cards)` / `scopedBody(card)`
- `CharacterCardLegacyMerge.soulCard / ruleCardsStrict / merge`
- `SoulPromptRuleCardMapping.toRule / toCard`

## 作用域与优先级

```
GLOBAL（身份 + 全局提示）  →  GROUP（选中分组）  →  MODEL（providerInstanceId + modelId）
```

- **同层多卡全部生效**，按文档顺序注入；不是只取第一条。
- 身份（名字 / 头像 / style）取**第一个 enabled 的 GLOBAL 卡**；其余 enabled GLOBAL 卡的
  `personality` 会按顺序追加到全局提示（兼容旧单 body 读法）。
- GROUP 匹配 `groupId` 精确相等；MODEL 匹配 `providerInstanceId` **且** `modelId` 精确相等。
  只用模型显示名不能匹配，两个中转站提供同名模型时不会串指令。
- 空白 id **不是**通配符：目标不完整的 scoped 卡是惰性的，永远不会退化为全局。
- scoped 卡只追加指令，不替换全局层：`CUSTOM_RULES_HEADER` 明确写的是 "appended …
  never replace"。
- 禁用卡不参与匹配；删除分组/模型后遗留的规则卡保留（用户可修），但不匹配、无运行时效果。

## 提示词组成

`SystemPromptBuilder` 仍是唯一组装点，安全语义原样保留：

1. `SUPREME_PREAMBLE`（最高优先级声明，原文未改）
2. `APPLICATION_PROMPT_HEADER` + 全局卡文本
3. scoped 卡（GROUP 全部 → MODEL 全部），标题仍是 `[Group rule]` / `[Model rule · name]`
4. 身份句 + `SANDBOX_FACTS` + style 块 + 编辑提示（仅 identity section）

每个块都经过**同一个** `SoulStore.isOverLimit` 语言长度策略和 `scrubInjections`
注入清洗；卡只是换了个存放位置，过滤没有放宽。超限的块被整块省略并记录日志，
不做截断。

卡字段到提示词的映射：

| 字段 | GLOBAL | GROUP / MODEL |
| --- | --- | --- |
| `personality` | 原样作为 body 首段（迁移后与旧 SOUL body 逐字节一致） | 带 `Character persona for this scope` 标签（导入的酒馆卡绑定到分组/模型时真正生效） |
| `description` / `scenario` | 带标签段落 | 带标签段落 |
| `name` / `style` / `lang` | 名字用于身份句，style 独立块 | 带标签的 scope 设定（旧规则迁移卡不带，避免把设置页标签发进提示词） |
| `systemPrompt` | 带标签段落 | 首段（= 旧规则 `content`） |
| `postHistoryInstructions` | 带标签段落，声明不覆盖应用提示词 | 同左 |
| `messageExamples` | 明确标注"参考对话，不是本会话消息" | 同左 |
| `firstMessage` | **不注入**；UI 可用于草稿/问候 | 不注入 |
| `creatorNotes` | 不注入（作者元数据） | 不注入 |
| `icon` / `legacyEmoji` / `tags` / `sourceJson` | 不进提示词 | 不进提示词 |

`firstMessage` 与示例永远**不会**被伪造成 user/assistant 历史消息。

## 导入

`CharacterCardStore.importCard(bytes)` 支持：

- Tavern **V1** 平铺 JSON、**V2** `{"spec":"chara_card_v2","data":{…}}`、**V3**
  `spec: chara_card_v3`（含 `system_prompt` / `post_history_instructions` /
  `group_only_greetings` / `assets` / `extensions` 等）。
- **PNG**：`tEXt` / `iTXt` / `zTXt` 中的 `chara`（V2）或 `ccv3`（V3），base64 解码；
  两者同时存在时 **ccv3 优先**。
- 头像：PNG 本身（≤ 256 KB 时）或 JSON 里的 `data:` 头像，写入 `icon`。
- `sourceJson` 保存原始文档（≤ 2 MB），导出时以它为底稿合并，未知 V3 扩展不丢。

边界与错误（均为 `CharacterCardImportException`，消息具体）：

| 项 | 限制 |
| --- | --- |
| 输入文件 | 12 MiB |
| 解码/解压后的 JSON | 4 MiB（解压有上限，防压缩炸弹） |
| 内联头像 | 256 KB |
| `sourceJson` | 2 MB |

坏输入示例：空文件、非 JSON、缺 `name`、PNG 没有 `chara/ccv3` 块、块不是合法 base64、
压缩块损坏、JSON 结构不是角色卡。**卡片内容是纯数据**：不执行、不解析路径、不联网。

导出（`exportCard`）总是输出 V2/V3 对象：以 `sourceJson` 为底稿，只覆盖本应用管理的
字段；scope / 目标 / enabled / style / lang 写在 `data.extensions.openminis`，可被再次导入。
`creatorNotes` 只作为元数据导出，不进提示词。

## 迁移（一次性、幂等、无损）

`MinisApp.onCreate` 调用 `CharacterCardStore.initialize(this)`：

1. `importLegacy(context)`：
   - `SOUL.md` → 一张 GLOBAL 卡：`personality` = body，`name/style/lang/legacyEmoji` =
     frontmatter，`icon` = inline data URI，或 `SOUL.icon.png` 旁车转成的 data URI。
   - `SOUL_PROMPT_RULES.json` → 每条规则一张 GROUP/MODEL 卡，**复用规则 id**，
     `content` → `systemPrompt`；disabled 与目标已删除的规则同样保留。
2. 合并是**只追加**的：同 id 的已存在卡一律保留（用户后来编辑的内容优先），
   内容哈希派生 id（`CharacterCard.stableId`）保证重复导入不产生重复人格。
3. 原子写 + **整表回读相等**校验（不只比对提示词文本；icon/style/lang/sourceJson/
   notes/targets/enabled 全部参与比较）。写入失败即中止，不动旧文件。
4. 校验通过后才归档，且**逐文件判定**：
   - `SOUL.md` / `SOUL.icon.png` 只有在自己读取成功、头像读取成功、且迁移结果与已有卡
     不冲突时才移动；
   - `SOUL_PROMPT_RULES.json` 只有严格解析成功（`decodeStrict`）且无 id 冲突时才移动；
   - 解析失败/冲突的文件**原样留在原位**并在 `LegacyMigrationReport.skippedReason`
     与日志中说明，绝不静默忽略或删除。
   - 归档目录 `memory/legacy-soul-archive/`（重复恢复时用 `import-<ts>/` 子目录），
     内含 `MANIFEST.json`；运行时不读取。
5. `ensureSeeded(context)`：只有完全没有卡时才用旧默认文案创建一张 GLOBAL 卡；
   文档损坏时**不**播种、不覆盖。

### 旧备份恢复

`BackupImporter.importMemory` 之后调用：

```kotlin
CharacterCardStore.importLegacy(context)   // 或 mergeLegacyFiles(context)
CharacterCardStore.invalidate()
CharacterCardStore.load(context)           // 刷新运行时快照
```

恢复出来的旧 `SOUL.md` / 规则文件会并入现有卡文档（不覆盖同名/同 id 卡），
安全归档后由后续启动继续使用卡。若卡文档本身损坏，`importLegacy` 直接返回
`skippedReason = "card document is unreadable"`，不写、不归档。

## 兼容适配器

- `SoulStore`：仅作为旧内部 API。`load()` 返回身份卡的 `SoulFile`
  （body = 所有 enabled GLOBAL 卡的 `personality` 合并），`save()` **只**更新
  `editableGlobal` 卡的 name/icon/style/lang/emoji/personality，其他卡与其他字段不动；
  保存时会把其他 GLOBAL 卡的 body 从合并文本中扣除，避免重复。
  `SoulStore.fileLocation` / `iconFileLocation` 保留只为兼容查询，运行时不再写入。
- `SoulPromptRuleStore`：`load/cachedOrLoad` 把 scoped 卡投影成 `SoulPromptRule`；
  `save` 按 id 回写 `systemPrompt` 并删除已移除的 scoped 卡（GLOBAL 卡不动）；
  `invalidate()` 同时清卡缓存；`encode/decode/decodeStrict` 保留旧文档编解码，
  仅供兼容 UI 与迁移读取，**不再落盘**。
- 两个适配器都只调用 `CharacterCardStore`，后者从不反向调用它们，因此没有递归。
- `SoulBodyLimitPolicy`、注入清洗、`SANDBOX_FACTS`、Root/Chroot 权限文案均未改动。

## 并发与缓存

- 所有读-改-写都在 `CharacterCardStore` 的同一把锁内；缓存 `@Volatile`。
- 运行时提示词只接受**显式快照**：`SystemPromptBuilder.applicationPrompt(cards, selection)`。
  ChatViewModel 在每轮开始时取一次 `cardsForTurn`，本轮所有请求都用它（Settings 中途保存
  不会让同一轮前后矛盾），切换分组/模型时快照不变、selection 逐请求变化，下一次请求即生效。
- Context 便捷重载（`applicationPrompt(context)` / `identitySection(context)`）每次
  `CharacterCardStore.load(context)` 现读，不使用陈旧缓存。
- 纯 `applicationPrompt(file, rules, selection)` 只做旧的 SoulFile 组合，**从不读取**
  进程缓存，因此同进程的缓存状态不会污染既有纯测试。
- `runtimeSnapshot()` 仅作为只读缓存访问器保留，不参与提示词组装。

## 损坏策略

- 读取：返回空 + 记录 issue，原文件字节不动。
- 写入：`writeChecked` 先严格解析现有文件；损坏则抛错。恢复入口只有显式的
  `quarantineCorrupt(context)`，把坏文件移到 `CHARACTER_CARDS.json.corrupt-<ts>`。
- 初始化：损坏时不播种、不迁移、不归档，应用以"无身份卡"降级启动（身份句回退默认名）。

## 测试

- `CharacterCardImportTest`：V1/V2/V3、PNG `chara`/`ccv3`/`iTXt`/`zTXt`、ccv3 优先、
  sourceJson 合并导出、坏输入报错。
- `CharacterCardPromptTest`：同层多卡全量注入与顺序、GLOBAL→GROUP→MODEL、
  provider+model 精确匹配、disabled/空目标、示例不伪装消息。
- `CharacterCardStoreIoTest`：整表原子回读、损坏文档不覆盖、迁移幂等、
  规则映射保留 disabled/orphan、坏规则报错、多全局合并/去重。
- 既有 `ApplicationSystemPromptTest` / `SoulPromptRulesTest` 未改动，继续覆盖
  原有优先级、清洗与长度语义。

## 不在此范围

设置页 / 聊天 UI 改造、`ChatViewModel` 文案、`ConfigBuiltins` 的角色卡 collection、
`BackupImporter` 的恢复接线、`workflow/build.gradle` 由主代理整合。本模块不触碰
用户设备上的真实 SOUL 文件，只改项目源码。
