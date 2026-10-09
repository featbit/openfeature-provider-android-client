# 核心 SDK 评估与身份契约

状态：2026-10-06 核心 SDK 0.2.0 已正式发布并完成公开下载、签名及独立消费验证。Provider 固定依赖 `co.featbit:featbit-client-android:0.2.0`，发布源码为 `7210251fbe5e3f9f452ef15e370da265f1009702`。本页描述已具备的核心接入契约，采用用户确定的 LaunchDarkly 风格类型与数值策略；替代此前 resolveDetail、ResolutionResult、ResolvedValue 和精确 JsonValue 的方案，不再要求这些新增 API。Provider 尚未实现，禁止引用 internal。发布证据见[实施基线](./implementation-plan.md#1-本次核对的基线)。

## 1. 复用公开评估 API

普通方法及 Detail 方法采用相同的类型规则，Provider 使用 Detail 结果判断成功或失败，不通过返回值是否等于 fallback 判断。

| Provider 请求 | 核心调用 | 行为 |
| --- | --- | --- |
| Boolean | boolVariationDetail | 只接受 boolean 声明 |
| String | stringVariationDetail | 只接受 string 声明，不自动序列化其他类型 |
| Int / Long / Double | numberVariationDetail | 只接受 number 声明，先解析为有限 Double |
| Object | jsonVariationDetail | 只接受 json 声明，返回 FbValue 树，数字为 Double |

核心通用 variation/variationDetail 按声明类型解析，不要求结果与 fallback.kind 一致。专用 jsonTextVariation 要求 json 声明，但保留原始文本，不验证 JSON 语法。allVariations 保留原文且不收集事件。

## 2. 数值与错误映射

保留现有 FbValue 与 Double 模型，不增加精确整数或 JSON 数字树。Provider 仅在 numberVariationDetail 成功后执行 Double.toInt()/toLong()：小数向零截断，越界按 JVM/Kotlin 转换饱和到边界；不承诺完整 Long 精度，不事后追加整数性、安全整数范围或精度检查。Double 解析失败或非有限值由核心返回 WRONG_TYPE。

JSON 数字也保持 Double；映射到 OpenFeature Value 时使用 Double 数字，不声称保留原文整数精度。JSON null、数组和对象按现有不可变 FbValue 模型递归映射。调用者提供的 OpenFeature fallback 在失败时原样保留，不先转成 Double 或 FbValue 再返回，避免破坏 Long 或复杂默认值。

| 核心 reason | Provider 处理 |
| --- | --- |
| MATCH | 返回转换后的值；缺少可靠来源信息时 reason=UNKNOWN、variant=null |
| WRONG_TYPE | TYPE_MISMATCH，保留原始 default；现有 API 不区分声明错误和内容解析错误，不猜测 PARSE_ERROR |
| FLAG_NOT_FOUND | FLAG_NOT_FOUND，保留原始 default |
| CLIENT_NOT_READY | PROVIDER_NOT_READY，保留原始 default |
| ERROR | GENERAL，保留原始 default |

关闭、终止和上下文可用性由 Provider 生命周期门禁检查；不把核心保留的关闭后本地读取行为当作 Provider Ready。

## 3. 同一评估中的类型判断与事件

1. 从核心不可变 view 读取当前记录。
2. 在 gate 外检查声明类型并解析；类型或解析失败保留为本次候选结果。
3. 回到 gate 确认 view 未变化；成功和失败都重试已过时的快照。
4. 对当前已确认的远端记录，沿用元数据、禁用、离线、关闭、去重和容量规则尝试准入事件。失败评估不自动禁止事件。
5. 返回成功值或原始 fallback + WRONG_TYPE。普通方法返回其中的 value。

事件描述服务端选中的 variation，不代表应用一定使用了它。FeatBit 现有协议没有本地类型错误原因或调用者 fallback 字段，本次不扩展协议、不伪造 variationId、不以 fallback 替换选中值。找不到 flag、归档、bootstrap、未确认缓存和本地源仍不产生此类评估事件。Provider 不补发或撤销核心事件。

## 4. Java/Kotlin 兼容边界

核心继续采用 Kotlin 实现、Java 可调用的公开 API。identifyContext 和 identifyAnonymousContext 直接属于 FeatBitClient，返回 IdentityReceipt；无需额外能力接口或类型转换。核心版本通过 Maven 精确依赖锁定，不能误用尚未包含声明类型检查的旧版。第三方 FeatBitClient 实现必须遵守这些契约。

## 5. 身份采用回执协议

identify 与 identifyAnonymous 都在后台准备并采用身份，再等待 ready。两者返回 TIMED_OUT 时，仅凭该结果不能判断采用是否已经发生。

0.2.0 的两项 Context 接口把采用操作单独结算：

1. 校验输入及 timeout、容量，建立 identity 请求序号；新请求可取代旧匿名准备。
2. 具名路径复用 enrich/contextKey；匿名路径复用持久化 repository.prepare/withCurrent，禁止 provider 创建匿名 ID。
3. 同一 gate 内确认仍是最新请求、未 close、未到采用 deadline；执行 adopt，生成 IdentityReceipt 并同步 settle SUCCESS。
4. 结果回调延迟不能把已成功采用改为 TIMED_OUT；Operation.getResult 始终保留成功回执。
5. deadline 先到、存储失败或被取代而未采用时 settle 对应失败，晚到准备不得再 adopt。若已 SUCCESS 后才出现新请求，保留原 SUCCESS，它只证明曾采用，不证明仍是当前身份。

Provider 对回执再检查自己的 RequestToken；它只对“当前请求的成功回执”设置 adoptedContext，然后另发 awaitReady。mode、后台、缓存命中、远端恢复不会创造身份回执。收到 SUCCESS 不等于 flag 可评估。

这两项接口的 timeout 只限制身份准备/采用，不包括等待远端数据；provider 的整体生命周期预算另行计算。操作成功可在调用返回前发生，但 observe 必须沿用核心异步回调契约。

## 6. 验证边界

核心验证覆盖声明类型不匹配、普通/Detail 一致、通用 fallback 类型无关、Double 舍入、错误评估事件、来源和元数据限制。Provider 后续还必须验证 Int/Long 截断和饱和、原始 default 保留、JSON 映射、error hooks、身份租约与关闭竞态。核心测试通过不代表 Provider 已完成或已发布。
