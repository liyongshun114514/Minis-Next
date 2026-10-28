# Minis Next · Native Intelligent UI

基于 Kairos 1.0.2 源码延续全部既有功能；仅显示品牌更名为 Minis Next、安装包名改为 `com.openminis.app.next`，版本 `1.15-ubuntu.8` / code 43，并新增本功能。内部 namespace、数据库、CLI、minis:// 协议、全局 SOUL、Root/PRoot/chroot 与子代理行为不因更名改写。使用新的稳定私有签名，不兼容旧 Next 或 Kairos 的签名；不得宣传为可直接覆盖旧安装。先在旧应用导出备份，再由用户决定安装/恢复，不自动迁移、不自动卸载。

## 来源与原生适配

参考 [CopilotKit/OpenIntelligentUI](https://github.com/CopilotKit/OpenIntelligentUI)，固定调研版本 `8391a94c1c3939c679e40ddd7bfc08a9ef690e70`（MIT，许可证副本 `docs/licenses/OpenIntelligentUI-MIT.txt`）。采用其 task-first / interactive answers 交互思想，为 Android Agent 实现声明式组件协议和 Compose 原生渲染。不是把 Next.js 网站放进 WebView，也不把 React 组件谎称为 Android 原生。

上游使用 CopilotKit、Python agent、Jev 路由和 sandboxed HTML/JavaScript；这里复用 Minis Next 已配置模型和原生工具调用，不部署上游服务器，不需要额外 Jev/OpenAI Key，不增加上游遥测。**不是 AG-UI、A2UI 或任意上游 HTML/JS 的完整协议兼容实现，也不是 Web 版的逐功能克隆。** 不解释、执行模型生成的脚本。现有网页预览/浏览器功能保留，但新卡片不走网页。

## 使用

直接对话，例如：“用交互卡片比较这几个套餐”“做一个可以调整人数和小费的分账器”“给我一个步骤清单”。系统提示词建议在合适场景主动调用 `intelligent_ui`；简单问候/事实仍可直接回答，卡片后可补充短说明。是否调用仍取决于模型能力，不能保证每次回答都生成卡片。

`intelligent_ui` 参数：`tool_title`、`document`（JSON 字符串或对象）。声明：`{"version":1,"title":"...","nodes":[...]}`。1–16 组件，64 KiB；组件有唯一 id、type、可选 title/text。工具严格校验后把完整文档写入普通工具结果，随对话数据库保存和恢复；取消、失败、残缺调用使用现有错误/运行 pill，不渲染不完整交互卡片。卡片不会被工具自动折叠。

## 原生组件

- `text`：纯文本；`stat`：数值摘要。
- `table`：可横向滚动表格，保留精确值。
- `chart`：Canvas 柱状/折线图，含负数；下方保留精确标签/数值供无障碍读取。
- `choices`：用户点选后作为**正常用户消息**继续对话。
- `form`：文本、数值、滑块、开关和单选；显式发送后继续对话。忙碌或暂停中的会话禁发跟进，不自动替代审批。
- `calculator`：修改输入后本地实时计算；受限算术解析器支持 `+ - * / ^`、变量和少量数学函数，没有 eval/脚本/命令/网络。非法输入、除零、超限表达式显示错误。
- `steps`：本地可勾选步骤；`diagram`：分步说明。
- `map`：经纬度点位/连接的**坐标示意图**，明确标识非地图瓦片、非核实导航路线；不宣称具备上游实时地图或照片。
- `rotation3d`：原生投影飞机的 pitch/roll/yaw 滑块和重置，用于轴旋转解释；不支持导入任意 3D 模型或上游 Web 动画。

输入本地 `rememberSaveable` 保留于正常 Compose 状态恢复；最终模型文档持久化在聊天记录。控件输入不是独立业务记录，不承诺在进程被强杀后所有未发送编辑都永久保存。没有后台自动执行、自动提交、自动授权；涉及实际变更继续使用现有工具与权限确认。

## 验证与构建

- 纯 Kotlin：`UiExpressionTest` / `IntelligentUiParserTest`。
- Robolectric + Compose：`IntelligentUiRenderTest` 验证真实控件显示、点击发送、忙碌禁发、计算更新和失败回退。
- Python：`python3 -m unittest discover -s scripts/tests -p test_intelligent_ui_contract.py -v`。
- 云端 `.github/workflows/android-ubuntu.yml`：Debug 测试与 Release 编译顺序执行，关键测试零失败/零跳过，签名/包名/镜像校验和最终生产解压通过后上传 Actions artifact。**不创建 Release，不创建版本标签。**
- 尚需用户安装后实机验收滚动、输入法、无障碍、屏幕旋转/深色主题和模型实际调用表现；云端测试不等同实机验收。
