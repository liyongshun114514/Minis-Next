# Minis Next 1.6.6 / build 57

## 默认通用余额

供应商保持原有 API 地址和已保存的 Key 即可；余额查询地址留空时，默认通用中转探测会自动识别 OpenAI-compatible billing，不需要另加余额地址、不需要另输 Key，也不需要登记站点域名或选择专用适配类型。旧的自定义余额地址作为可选覆盖保留，其 billing 响应复用同一解析器。

探测顺序为 Sub2API → OpenAI-compatible billing → New API → LiteLLM。billing 优先于 New API 的站点自定义 quota 单位；若站点只返回原始 quota 且无标准美元账单，仍保留原始单位，不擅自按比例换成美元。其他真正的订阅窗口、令牌限额不被强制伪装成账户余额。

按供应商 API 基地址保留网关路径前缀、去掉末尾 `/v1`，自动探测 `/v1/dashboard/billing/subscription`，未找到或返回非账单结构时兼容同前缀的 `/dashboard/billing/subscription`。仅在没有现成 balance 或用量时补查同一路径的 `usage`；所有请求复用现有 Key 和请求头，禁止跟随重定向，不从响应中跟随其他域名。

## 原版余额行

标准美元账单统一解析为 `prepaidBalance + Currency(USD)`，通过原版 UI 显示：

**余额 · USD　US$110.08**（金额仅为示例；币种符号继续由原版本地化格式决定。）

不是“订阅/用量/额度上限/已用/剩余”进度行：`hard_limit_usd` 只参与计算，不作为余额或 UI 的 total。计算仍为 `hard_limit_usd - total_usage / 100`，`used_usd` 则直接以美元解析。支持根对象或 `data` 包装、内联用量、显式余额优先于无限上限哨兵。缺少实际用量时报错或继续其他通用探测，不把未知用量当零、不把总上限当余额。

未改动 `QuotaUi.kt`、`QuotaPersistence.kt`、金额符号或资源字符串；通过正确的通用余额类型复用原版“余额 · USD”行。

## 升级与验证

通用探测能力键升级，旧的 New API 类型缓存和 24 小时失败缓存不会阻止本版重新探测；新能力记录不存在时不等待旧配额快照的刷新计时。既有供应商设置、Key 和手填覆盖地址不清除。

回归覆盖多个任意域名及网关前缀、无需自定义余额地址、账单优先级、同源双请求、禁止跳转、根路径回退、内联与 data 用量、美元精度、无限哨兵、缺失数据，以及已有手填覆盖的兼容。GitHub Actions 增加共享 BillingBalance 和原版币种摘要测试。

本地最终验证：817 个 Kotlin 文件及 4 个 Java 文件全源码类型检查通过（kotlinc / javac 均 exit 0）。6 组 JVM 回归 97/97 通过：BillingBalance 14、CustomBalanceQuota 19、ProviderQuota 24、QuotaRelay 19、QuotaHeadlineCurrency 9、Ubuntu6QuotaChannels 12，0 failed / 0 skipped。版本/界面/提示词 Python 契约 14/14 通过，提示词目录校验无错误，`git diff --check` 通过。

本地源码类型编译和 JVM 测试不等于 AGP/KSP/DEX/APK 构建或实机显示验收；云端 APK 构建由 Actions 执行，用户自行跟踪。本轮不使用真实供应商 Key 联网、不安装 APK。
