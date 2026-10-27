# 系统提示词设置界面重构（Ubuntu6）

基线：`be25ce6`。本次只改 UI 与对应测试，不触碰 `SoulStore` / `SoulPromptRules`
数据层、其他页面、Gradle、workflow 与版本号。

## 1. 目标与现状

原页面的提示词区域用 `TabRow` 把「全局」和「自定义」分成两个页面：

- 全局编辑器占据整个页面，保存按钮在 AppBar，视觉上像是同时保存规则；
- 自定义规则列表只有名称、目标一行和 3 行内容，范围不够醒目；
- 规则编辑器在滚动对话框里再放一个 `TabRow` + `DropdownMenu`，层级深，
  模型多时下拉菜单体验差；
- 规则没有顺序概念，而运行时注入顺序恰恰依赖存储列表顺序。

## 2. 新布局

页面保持一个滚动面，自上而下：

1. **身份**（头像 + 名称）——原样保留。
2. **System Prompt（全局）**
   - 头部：`Global` 徽标 + 一句话范围说明；
   - 编辑器：仍为 `DocumentEditorField`，仍写 `SOUL.md` body；
   - 底部：保存状态（`Saved` / `Unsaved changes`）+ 该卡片自己的 `Save`；
   - 页脚：说明注入顺序「全局 → 分组规则 → 模型规则」。
3. **Custom rules（自定义规则）**
   - 顶部摘要：`N rules · M enabled` + 「同一范围内列表顺序即附加顺序」；
   - 空态与「Add rule」按钮；
   - 规则按范围分成两个可见区块：`Group rules (n)` 在前、`Model rules (n)` 在后，
     与 `SoulPromptRuleResolver` 的注入顺序一致；
   - 每条规则一行式卡片：
     - `#序号` + 名称（空名称显示 Untitled rule）；
     - 范围 chip（Group / Model）+ 目标名称（`Group: X` / `Model: Y`）；
     - 目标被删除时显示红色 `Target no longer exists`，不会退化成全局；
     - 停用规则额外显示 `Disabled` chip，内容摘要变淡；
     - 右侧启停开关；
     - 两行内容摘要（压缩空白 + 160 字符截断）；
     - 上移 / 下移（仅在同一范围内可用）、编辑、删除。
4. **规则编辑器**（`SoulPromptRuleEditorDialog`）
   - 顶部：启用开关 + 说明「停用后仍保留，但不会附加」；
   - `Applies to`：两段式 `SingleChoiceSegmentedButtonRow`（Group / Model），
     作为规则字段而不是页面导航；
   - `Target`：单行目标卡片，点击打开独立的目标选择对话框；
   - 目标选择器：单选列表（组或模型），可滚动，不再使用嵌套 `DropdownMenu`；
   - 名称（可选）→ 提示词内容；
   - 校验、默认目标、缺失目标保留逻辑与原实现一致。

## 3. 语义保持不变

- 全局提示词仍是 `SOUL.md` 的 Markdown body，保存仍走
  `SoulStore.save(context, currentFile)`，长度门禁仍调用
  `SoulStore.isOverLimit`。
- 自定义规则仍由 `SoulPromptRuleStore` 读写，仍然每次修改立即持久化；
  没有新增草稿状态，也没有改 JSON 格式。
- 目标匹配仍只认 `groupId` 或 (`providerInstanceId`, `modelId`)；没有
  GLOBAL 目标；目标被删除时保留原 id、显示缺失、禁止保存。
- 注入顺序仍由存储列表顺序 + 「分组先于模型」决定。上移/下移只交换**同一
  范围**内的元素，其他范围的元素的绝对位置不变；不做任何排序（无
  `sortedBy`），因此不会改变提示词字节。
- 删除、启停、编辑、新增仍走原来的 `persistRules` 串行写路径。

## 4. 文件

新增（仅本 UI）：

- `src/android/app/src/main/java/com/openminis/app/ui/settings/SoulPromptRuleUi.kt`
  —— 纯 Kotlin：按范围取列表、启用计数、内容摘要、同范围内移动、目标解析。
- `src/android/app/src/main/java/com/openminis/app/ui/settings/SoulPromptRulesSection.kt`
  —— 自定义规则摘要、范围区块、规则行、上移/下移、编辑/删除入口。
- `src/android/app/src/main/res/values/prompt_ui.xml`
- `src/android/app/src/main/res/values-zh/prompt_ui.xml`
- `src/android/app/src/test/java/com/openminis/app/ui/settings/SoulPromptRuleUiTest.kt`

修改：

- `ui/settings/SoulSettingsScreen.kt`：去掉 TabRow 与 `promptTab`；全局编辑器
  与自定义规则改为两个相邻 `SettingsSection`；保存按钮移入全局卡片；规则区
  接上新增的 `SoulCustomRulesSection`。
- `ui/settings/SoulPromptRuleEditor.kt`：重构为「启用 → 范围 → 目标 → 名称 →
  内容」，目标选择改为独立选择对话框。
- `ui/settings/SoulSettingsPageContractTest.kt`：按新结构更新契约断言。

未修改：`SoulStore.kt`、`SoulPromptRules.kt`、`strings.xml`、导航、Gradle、
workflow、版本号，未提交 git。

## 5. 实际验证

在 Alpine aarch64 PRoot 内执行：

1. 资源 XML：`values/prompt_ui.xml` 与 `values-zh/prompt_ui.xml` 均可被
   Python ElementTree 解析，各 18 条 string，键集合完全一致。
2. 引用检查：改动/新增的三个 Kotlin 源文件（Screen / Section / Editor）中的
   全部 `R.string.*` 引用（67 个）均能在 `res/values/*.xml` 找到定义；
   新增键没有与既有资源重名。
3. 纯逻辑类型与行为检查：用 Kotlin 2.0.21 编译器单独编译
   `SoulPromptRuleUi.kt`（配最小数据层桩），无错误；随后运行检查程序，
   断言同范围上移/下移、跨范围 no-op、按范围取列表、摘要压缩截断、目标
   缺失解析，全部通过（输出 `SoulPromptRuleUi checks passed`）。
4. 括号配对扫描：四个改动/新增 Kotlin 文件的 `()`/`[]`/`{}` 全部配对。
5. 编译探针：使用共享缓存与 aarch64 aapt2 运行
   `:app:compileGithubDebugKotlin`。资源处理、`kspGithubDebugKotlin` 通过，
   Kotlin 编译任务报错仅来自缺失的 gomobile 生成绑定
   （`RcloneBridge.kt` 的 `rcloneSetSocks`、`TailscaleNode.kt` /
   `TailscalePathMonitor.kt` 的 `ts*` / `net*`），**改动文件 0 条错误**。
   按调度方要求已停止本机完整 Gradle 类型检查，改由 GitHub Actions 云编译
   统一执行；因此本机结果不等同于完整编译通过。
6. 未执行：`SoulPromptRuleUiTest` 与页面契约测试（需要完整 Gradle 单测），
   留待云端 CI。

本机残留：

- 未删除 `src/android/app/build` 与 `src/android/.kotlin`：它们是仓库原有
  构建产物，且当时另一个并行任务正在同一仓库运行 Gradle 单测，删除会破坏
  其运行；两者均可再生且未纳入 git。
- 已删除本次为解析依赖临时创建的 `app/libs/rclone.aar` 符号链接。

## 6. 未完成 / 限制

- 没有做 Compose 预览或设备截图验证；纯 UI 视觉需在云端构建后人工确认。
- 模型目标选择器未加搜索框；列表本身可滚动，数量极大时仍可定位但需要滑动。
- 空范围区块不显示（例如没有模型规则时不显示 `Model rules (0)`），避免空标题。
- 上移/下移不跨范围：跨范围交换不会改变注入结果，因此不提供该操作。

