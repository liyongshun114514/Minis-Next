# Android 1.7.0 — SOUL 档案与分组头像

版本：1.7.0 / versionCode 58。源码基线：`ec2caea`（1.6.6）。

## 使用方式

设置中的 **SOUL** 入口管理多个档案。每个档案有独立名称、头像、风格和提示词正文。在对话右上角三点菜单选择 SOUL，该选择只属于此对话，关闭重开仍保留。首条消息前选择的 SOUL 会跟随新会话保存。

默认档案继续使用 `minis-global/memory/SOUL.md` 和原有头像文件。额外档案及会话选择存放在 `minis-global/memory/souls/`，随记忆目录备份。默认档案不能删除；删除额外档案后，使用它的对话回退至默认档案。`minis-config soul.*` 继续编辑默认档案。

SOUL 正文通过供应商原生系统消息通道发送。每轮读取一次所选档案，身份句与正文共用该快照，重试或模型切换继续复用。排队等待期间的档案切换会在该轮实际开始时解析。用户保存的正文不再按关键词拒绝或删行。工具权限、Root 三态和操作确认仍由对应代码执行。

聊天名称和头像显示本对话当前所选 SOUL，历史气泡也随当前选择显示；消息未保存逐条 SOUL 身份。运行中的请求继续使用已经冻结的提示词，选择器所示“下一条消息生效”针对模型提示词。草稿升为正式会话的迁移与连续点选使用同一写入队列，最后一次选择优先。

独立工作区指令编辑页、AGENTS.md / CLAUDE.md 指令链及独占开关已移除。非空的旧全局 `memory/AGENTS.md` 会一次性复制为额外 SOUL；原文件保留，导入后在对话菜单选择该档案。删除导入档案后不会再次生成。

模型分组的头像在分组详情中设置：自动匹配、内置品牌图标或相册图片。手动选择优先；恢复自动后按当前分组名称匹配，重命名即时更新自动图标。无匹配与加载失败统一使用默认分组图标。

分组头像保存在 `ModelGroup.avatar`，兼容旧 JSON 配置与备份。`provider.db` 从 5 升至 6，仅追加 nullable `avatar` 列，旧行按自动匹配处理。品牌图标离线随包提供，来源与许可见 [model-icons.md](model-icons.md) 和根目录 `THIRD_PARTY_LICENSES.md`。

## 验证记录

接管后重新完成 820 Kotlin + 4 Java 的生产源码编译检查；27 类聚焦 JUnit 中 214 项通过、18 项标记 ENV-GAP（Robolectric 原生图形运行时不支持 Linux/aarch64），没有剩余普通 JUnit 断言失败。测试运行器使用 AGP 生成的 mockable android.jar，真实 SQLite JDBC 执行数据库迁移，未替换业务方法。详细结果见 [验收证据](evidence/soul-groups-1.7.0/README.md)。

提示词目录 354 entries / 319 call sites / 44 shell rules、中文 2496 strings + 13 plurals、图标资源、125 项界面静态契约及版本/Actions 资源契约均通过。源码编译检查不等同于 APK 构建；本机缺 rclone AAR 等完整打包输入，APK 与 18 项原生图形测试由 GitHub Actions 执行。未安装或实机操作不计为通过。

## 临时文件

本轮与接管前临时编译根 `workspace/tmp/minis-resume-20261116/`、`workspace/tmp/minis-takeover-20261116/`、`/tmp/minis-resume-20261116-verify` 及已确认归属的 `/tmp/coilx`、`/tmp/agp`、`/tmp/agp2` 均已清理；正式源码和验收证据保留。终端 `/tmp` 仅留共享的 `hsperfdata_root`，`/var/tmp` 为空。先前 `workspace/tmp/minis-soul-20261116/` readiness 目录及旧 Gradle 空闲进程也已清理。
