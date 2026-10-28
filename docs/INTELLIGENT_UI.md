# Minis Next · Native Intelligent UI

基于 Kairos 1.0.2 源码延续全部既有功能；仅显示品牌更名为 Minis Next、安装包名改为 `com.openminis.app.next`，版本 `1.2` / code 47（公开版本纯数字迭代，内部 Android 升级序号保持递增；本版规范 Android `Ubuntu-rootfs` 目录，保留 1.1 交互功能），并新增本功能。内部 namespace、数据库、CLI、minis:// 协议、全局 SOUL、Root/PRoot/chroot 与子代理行为不因更名改写。使用新的稳定私有签名，不兼容旧 Next 或 Kairos 的签名；不得宣传为可直接覆盖旧安装。先在旧应用导出备份，再由用户决定安装/恢复，不自动迁移、不自动卸载。

## 来源与原生适配

参考 [CopilotKit/OpenIntelligentUI](https://github.com/CopilotKit/OpenIntelligentUI)，固定调研版本 `8391a94c1c3939c679e40ddd7bfc08a9ef690e70`（MIT，许可证副本 `docs/licenses/OpenIntelligentUI-MIT.txt`）。采用其 task-first / interactive answers 交互思想，为 Android Agent 实现声明式组件协议和 Compose 原生渲染。不是把 Next.js 网站放进 WebView，也不把 React 组件谎称为 Android 原生。

上游使用 CopilotKit、Python agent、Jev 路由和 sandboxed HTML/JavaScript；这里复用 Minis Next 已配置模型和原生工具调用，不部署上游服务器，不需要额外 Jev/OpenAI Key，不增加上游遥测。**不是 AG-UI、A2UI 或任意上游 HTML/JS 的完整协议兼容实现，也不是 Web 版的逐功能克隆。** 不解释、执行模型生成的脚本。现有网页预览/浏览器功能保留，但新卡片不走网页。

## 使用

直接对话，例如：“用交互卡片比较这几个套餐”“做一个可以调整人数和小费的分账器”“给我一个步骤清单”。系统提示词建议在合适场景主动调用 `intelligent_ui`；简单问候/事实仍可直接回答，卡片后可补充短说明。是否调用仍取决于模型能力，不能保证每次回答都生成卡片。

`intelligent_ui` 参数：`tool_title`、`document`（JSON 字符串或对象），或 `preset`（与 document 互斥）。`preset: all` 为八类组件和 A–F 六个综合实例的原生分区展示。每张卡片右上角“组件展示”也可直接打开，无需模型支持额外 API。声明：`{"version":1,"title":"...","nodes":[...]}`。1–32 顶层、至多128总组件、5层嵌套，192 KiB；组件有唯一 id、type、可选 title/text。工具严格校验后把完整文档写入普通工具结果，随对话数据库保存和恢复；取消、失败、残缺调用使用现有错误/运行 pill，不渲染不完整交互卡片。卡片不会被工具自动折叠。

## 原生组件

- `text`：纯文本；`stat`：数值摘要。
- `table`：可横向滚动表格，保留精确值。
- `chart`：Canvas 柱状/折线图，含负数；下方保留精确标签/数值供无障碍读取。
- `choices`：用户点选后作为**正常用户消息**继续对话。
- `form`：文本、数值、滑块、开关和单选；显式发送后继续对话。忙碌或暂停中的会话禁发跟进，不自动替代审批。
- `calculator`：修改输入后本地实时计算；受限算术解析器支持 `+ - * / ^`、变量和三角/反三角、双曲、对数/指数、取整/阶乘等数学函数，没有 eval/脚本/命令/网络。非法输入、除零、超限表达式显示错误。
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

## 扩展目录与附件逐项对照

公开参考已联网核对：
- [Intelligent UI in ChatGPT（官方帮助）](https://help.openai.com/en/articles/20001598-intelligent-ui-in-chatgpt)：根据任务组合文字、图形、可点击按钮、表单、图表与交互解释；简单问题仍可纯文字。部分组件状态可在线程刷新后保留，非跨线程共享。本文不是其内部 SDK 的公开承诺。
- [OpenAI UI guidelines](https://developers.openai.com/plugins/concepts/ui-guidelines)：inline cards、carousel、展开工作区、明确动作、系统字体/暗色/响应式及可访问性。
- [OpenAI Apps SDK UI](https://openai.github.io/apps-sdk-ui/?path=/docs/overview-introduction--docs) 与 [源码](https://github.com/openai/apps-sdk-ui)：公开 Input/Textarea/RadioGroup/Checkbox/Select/SegmentedControl/Slider/DatePicker/Popover/Tooltip 等。该库是 React/Tailwind，未导入 Android、未访问隐藏 API；这里独立 Compose 实现对应交互，不承诺 ChatGPT 产品内部能力或协议完全兼容。

|附件类别|原生实现和实际行为|
|---|---|
|1 信息卡片|`card` 普通/图标/图片/横纵向、产品/人物/地点/摘要/推荐/状态/警告；`stat` 指标；点击展开详情，多列组合|
|2 布局导航|`section/grid/masonry/row/carousel/tabs/accordion/timeline/popover/tooltip/details`；触屏说明用点击弹层；`wizard/progress` 流程进度。瀑布分组是原生分栏，不是浏览器瀑布流|
|3 输入表单|`form` 的 text/textarea/search/number/slider/toggle/select/radio/checkbox/multiselect/segmented/date；必填/禁用/错误/数值范围校验；发送/取消/重置；日期使用原生 Material picker|
|4 交互工具|`calculator` 多输出及变量联动、`converter` 长度/质量/温度/存储、`compare` 同单位单价排序、`budget` 收支比例余额、`directory` 搜索过滤排序详情、`decision` 动态权重、`quiz` 本地练习反馈重置、`wizard` 本地分页向导。均真实本地计算，不等模型往返|
|5 数据可视化|`chart` bar/line/pie/scatter/histogram/ranking；点击选择精确数据、可切换多系列图例；`table/trend/progress/before_after`。直方图输入为已分桶计数，不冒充自动统计；坐标/单位/图例/精确值可见|
|6 媒体|`image/gallery` 单图/网格/轮播/说明，产品图列表；`video` 原生 VideoView 显式点播/停止/外部播放器/错误提示，无自动播放；`map` 地点坐标示意而非地图瓦片或已核实路线|
|7 结果操作|`alert` 四状态、`actions` 复制剪贴板/展开详情/明确跟进/打开HTTPS、`sources` 引用；可本地确认/取消，不把本地确认视为外部操作授权|
|8 综合界面|预设 dashboard/comparison/budget/quiz/directory/panel 对应 A–F，全部使用上面真实控件。all 展示用分类标签，不挤成一张长表|

价格比较中 `price` 是整份套餐/包装的总价，`quantity` 是所含计量单位数，单价=price/quantity；不同单位分组比较。决策分数及权重为0–100。示例统计、价格、测试成绩都明确是合成本地示例，练习不连接真实考试系统、不修改真实成绩。网络示例图片/视频不代表实时地点或官方实时数据。

媒体需要网络和设备解码支持；生产 Coil/VideoView 可能受网络策略、地址可用性、重定向影响，不承诺任意URL都可用。没有在线地图服务/API Key、直播或任意3D模型引擎。不能实现的部分明确标注，不用网页假装原生。

表单输入与选中状态使用 Compose `rememberSaveable`；对话数据库持久化的是生成文档，不是独立账目或业务记录。展示不会创建购买、预约或授权；真正工具操作仍走原工具与审批。

扩展回归：`IntelligentUiExtendedParserTest`、`IntelligentUiToolLogicTest`、`IntelligentUiToolsRenderTest`、`IntelligentUiRichRenderTest`、`IntelligentUiFieldsRenderTest` 加入云端零跳过门槛；包含真实输入、选择、预算联动、目录筛选、图例、画廊、视频不自动播放、表单必填/取消/显式发送、标签切换等测试。云端通过不代表已安装或真机全部验收。


## 1.0：数学交互与外观修复

- 外层智能卡片改为显式主题 surface、零 tonal/shadow elevation 与细边框，避免长卡片在聊天列表投出大块灰影。保留弹层正常行为，不通过取消全局主题或移除暗色模式掩盖问题。
- 1.1 按最新要求回退输入框到原来的 20dp 圆角，左右仍 12dp；仅保留并增加底部抬高，从安全区域或键盘上方留 16dp（较上一版多 4dp）。导航栏 inset 仅加一次，不改草稿/附件/发送逻辑。
- 公开 versionName 从 1.0 纯数字迭代到 1.1，versionCode 递增至 46；同上一版 Next 正式签名，不能覆盖更早不同签名应用。

### 1.1 交互修复

- 函数/隐式图支持点按或拖动探点，图内拖动不再被聊天列表滚动抢走；触摸与绘制共用密度和轴槽坐标。参数/极坐标按最近可见线段命中真实参数，不再把横坐标误当作 t。
- Intelligent UI 滑块实时连续调节，表单旧 step 不再产生固定档位或松手吸附；需要整数的数学运算仍执行自身参数合法性校验，不伪造整数结果。
- 新增 UiPlotGeometryTest 与 UiMathInteractionRenderTest，覆盖参数圆/极坐标顶部命中、坐标更新和滚动容器内真实拖动。云端真实 UI 测试仍是最终验收要求。

### 原生数学（`type: math`）

`preset: math` 展示 **13 种模式**，`preset: all` 中增加数学分类。此处“覆盖常用数学展示”不表示支持所有数学问题，也不是完整 TeX、CAS、自动证明器或任意代码计算环境。

|mode|可见、可操作行为|
|---|---|
|formula|原生有限公式排版：分数、根式、上下标、求和/乘积/积分/极限符号、矩阵、希腊字母、题干/结果/有序步骤。步骤内容由文档提供，不冒充引擎自动证明|
|function_plot|本地采样 y=f(x)，网格、坐标轴、探点、参数滑块即时重绘。多项式、sin/cos/tan、ln/log/exp 等不用模型或 Python 往返|
|parametric / polar|x(t),y(t) 参数曲线与 r(t) 极坐标，参数范围、点值、滑块联动；角度为弧度|
|implicit|F(x,y) 与 0 的等式轮廓/不等式区域，有限网格数值近似，不是精确解析边界|
|surface3d|z=f(x,y) 原生线框与视角调整；不导入任意模型/网格|
|calculus|数值导数、定积分与切线表达式；明确近似及定义域/收敛/预算错误，不宣称符号求导、不定积分|
|equation|在有限实区间内数值搜索实根；可能遗漏重根/窄根，不把“未找到”说成“无解”|
|matrix|2–6阶方阵的行列式、逆矩阵、线性方程组唯一解；奇异/病态错误明确|
|statistics|最多64个输入值的均值、中位数、最值、样本方差(n−1)、样本标准差；不是推断统计或无限数据分析|
|probability|排列/组合、二项分布概率、期望和方差；有界 n/k/p，不把近似浮点值当任意精度整数|
|geometry|可调圆半径、三角形边长、面积/周长/角度及示意图，非法边长报错|
|sequence|等差、等比、Fibonacci、自定义有界递推、前N项和与趋势图，最多40项|

公共 `sliders:[{name,label,min,max,value}]` 最多6个，合法变量名且不覆盖 x/y/z/pi/e/tau。每个表达式最多512字符，无 eval/JS/反射/命令/网络。`+ - * / ^` 与显式乘号、科学计数法、常量 pi/e/tau；幂优先级满足 `-x^2=-(x^2)`、`(-x)^2`、`2^-2`。`log(x)` 为常用对数，`ln(x)` 为自然对数，`log(x,b)` 指定底数。阶乘仅非负整数且有限范围。

绘图是固定工作量的有界采样，不是无限精度连续求解；参数改变在本地重新采样，绘制帧复用结果。定义域缺口/极点断线，超范围曲线裁切，不将无穷或错误画成有效值。不可计算时显示错误，不伪造结果。旧聊天中的模型文字不会自动重写，重新生成交互卡片才能使用新协议。

新增真实验收：UiMathEngineTest、UiMathSchemaTest、UiMathRenderTest、IntelligentUiSurfaceTest（明暗主题真实像素）、ComposerSurfaceGeometryTest，全部进入云端零跳过门控。完整编译/测试/签名与生产镜像验收通过只表示构建通过，未安装、未进行实机视觉验收。
