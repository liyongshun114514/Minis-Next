# Minis Next 1.6.5 / build 56

余额查询地址支持只填写 HTTPS 站点、`/v1` 基地址或完整 billing subscription / usage 地址，自动复用当前供应商已保存的 API Key，无需再次填写密钥。升级后会重新探测旧版缓存为不支持的自定义接口。

STA1N 示例：`https://sta1n.ai`、`https://cdn.sta1n.cn`、`https://sta1n.ai/v1` 或 `https://sta1n.ai/v1/dashboard/billing/subscription`。根地址及 `/v1` 自动补成 `/v1/dashboard/billing/subscription`，不会重复拼接 `/v1`。

当 subscription 只返回总额度时，自动向同源、同路径前缀的 `usage` 接口补查，余额为 `hard_limit_usd - total_usage / 100`，单位 USD。已有 `total_usage` 或 `used_usd` 的响应无需额外请求。明确返回的 `balance` 优先使用。用量查询失败或缺少金额时报告实际错误，不把总额度或未知用量显示为余额。

两次请求均复用供应商鉴权与已有请求头、禁止跳转；URL 查询参数保留，日志和来源记录继续隐藏查询参数。支持原来的非 billing 自定义余额接口；清空地址仍沿用自动识别。

按用户要求保留原版配额卡片、摘要、币种格式和显示样式。未更改货币换算，也不将站内饼干或未知 quota 单位直接标成美元。

验证：全源码 Kotlin/Java 类型编译通过。`CustomBalanceQuotaTest` 19/19、`ProviderQuotaTest` 24/24、`QuotaRelayTest` 7/7、`QuotaHeadlineCurrencyTest` 9/9，共 59/59 通过；覆盖基地址补全、subscription 与 usage 配对、保存密钥和已有请求头复用、同源和禁止跳转、data 包装、美元精度、usage 失败、已有 usage 不重复查询。版本/界面契约 10/10、提示词目录测试 4/4 和目录元数据校验通过。

额外运行 `QuotaHintsTest` 5 项中 4 项通过，旧的导航圆点源码断言失败；已核对它检查的 `ChatScreen.kt` 与基线 HEAD 完全一致，基线同一断言条件也为 false。本次不修改导航显示。

GitHub Actions 原有 `*CustomBalance*` 过滤器会运行新增用例，无需重复 dispatch。云端构建由用户自行跟踪，本地类型编译和 JVM 回归不代表 APK 构建或实机验证；本轮未使用真实密钥重新请求 STA1N（未设置 STA1N_KEY），未安装 APK。
