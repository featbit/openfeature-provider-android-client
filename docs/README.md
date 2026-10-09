# FeatBit OpenFeature Android Provider 设计

设计日期：2026-10-05；基线更新：2026-10-06；设计补充：2026-10-09。状态：详细架构设计待实现验证；Provider 尚未实现、编译或发布。核心 SDK 0.2.0 已发布并验证。

目标是在独立 Android AAR 中实现 OpenFeature Kotlin `FeatureProvider`，复用 FeatBit Android SDK 的同步、缓存、身份、生命周期与事件能力。实现语言为 Kotlin，新增公共构造及扩展 API 兼容 Java 调用；OpenFeature 自身的 suspend/Flow 接口保持其原有调用方式。

## 阅读顺序

1. [详细架构](./architecture.md)：组件、状态机、内部契约、并发、资源、事件与关闭协议。
2. [公共 API](./api-design.md)：需要公开的类型、方法、配置及使用流程。
3. [行为规范](./behavior-design.md)：上下文、评估、错误和 Track 映射。
4. [核心 SDK 接入契约](./core-sdk-contract.md)：声明类型检查、Double 数值模型、身份采用回执及兼容边界。
5. [详细验收协议](./architecture-validation.md)：不变量、确定性竞态用例和发布闸门。
6. [依据、差距与实施验收](./implementation-plan.md)：固定源码基线、实施顺序和总体验收清单。

## 推荐方向

| 事项 | 设计建议 |
| --- | --- |
| 发布坐标 | 沿用核心 SDK 架构中已确定的 `co.featbit:featbit-openfeature-provider-android:<version>` |
| 包名 | `co.featbit.openfeature.android`，本次建议 |
| 依赖基线 | 核心固定为已发布并验证的 `co.featbit:featbit-client-android:0.2.0`（G01 已满足）；OpenFeature 0.9.0 用于开发/问题复现，正式 `dev.openfeature:kotlin-sdk` 版本仍需包含状态修正并通过 G02 |
| 平台 | Android，minSdk 21、JVM 11 字节码、Kotlin 2.2.10 为设计基线；构建 JDK/AGP/Gradle 组合需要消费者验证 |
| 所有权 | 一个 provider 创建并独占一个 FeatBitClient；首版不接管外部运行中的 client |
| 用户模型 | OpenFeature 当前 EvaluationContext 是身份来源；切换通过 onContextSet → 采用回执 → awaitReady 完成 |
| 配置方式 | 使用接收已映射 User 的 options factory，复用 ClientOptions，不复制全部 SDK 配置 |
| 身份缺省 | 默认要求 targetingKey；可显式开启核心 SDK 的持久化匿名身份 |
| 评估 | Boolean、String、Int、Long、Double、Object 六类，返回完整错误信息，保持调用者默认值 |
| 事件 | Ready、Stale、Error、ConfigurationChanged；上下文 Reconciling 由 OpenFeature 管理 |
| 扩展 API | 连接信息、offline/online、flush、clearCache、可观察完成的 close |

## 评审重点

2026-10-06 已发布的核心 SDK 0.2.0 包含统一声明类型检查、Double 数值模型、失败评估的选中 variation 事件策略，以及两项身份采用接口。Provider 不再要求精确 Long 或失败零事件，详见[核心接入契约](./core-sdk-contract.md)。这表示采用了允许精度损失的数值方案，不表示已经实现精确 Long 解析。

G01 核心能力已满足，正式产物、签名和独立 Java/Kotlin 消费验证依据见[基线与证据](./implementation-plan.md#1-本次核对的基线)。OpenFeature 当前正式版仍为 0.9.0，初始化/上下文完成结果覆盖更新状态的源码时序问题仍在；本项目尚未运行复现测试，G02 未通过。所需仲裁规则见[详细架构](./architecture.md)。取得实现授权后可以开始 Provider 开发并进行最小复现，不必继续等待核心发布；正式发布仍须通过 G02 及 Provider 自身验收。

另外，FeatBit Track 目前只接受事件名称和数值，不能携带 OpenFeature TrackingEventDetails 的任意结构属性；首版会明确披露该限制。

Provider 新增 API 和策略仍是设计提案；核心 SDK API 已存在于 0.2.0。核心的发布与测试证据仅用于确认 G01，不替代本项目的集成和发布验收。

## 2026-10-09 设计补充

- 模式操作结果与实际模式分开处理；失败或超时后重新确认当前身份的可读状态，不自动回滚。
- 扩展操作对外预算包含排队；提交前超时不执行，提交后超时不取消核心副作用。
- observe 不设人数硬上限；保留每个订阅者的有界合并槽和取消清理。
- factory 开始后固定创建身份，只创建一次，再协调最新用户；明确核心可能先同步创建身份。
- Track 统一转换为有限 Double，接受精度损失和下溢；非有限结果或转换异常丢弃。

对应新增 R19–R24。以上是 Provider 设计决定，不需要修改核心 SDK；尚未实现或运行这些测试。G02 的 OpenFeature 状态验收要求保持不变。
