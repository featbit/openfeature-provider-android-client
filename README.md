# FeatBit OpenFeature Android Provider

基于 FeatBit Android SDK 的 OpenFeature Kotlin provider，Kotlin 实现，构造及扩展 API 支持 Java 调用。

当前为 `0.1.0-SNAPSHOT` 开发实现，尚未正式发布。依赖 FeatBit `0.2.0` 和 OpenFeature Kotlin `0.9.0`。已复现 OpenFeature 的生命周期返回覆盖 Stale/Fatal 状态问题：provider 自身会检查评估准入，但上游公开状态可能不准确。正式发布仍受 G02 阻挡，详见[验证记录](docs/verification.md)。

## 使用

本地 AAR 坐标：`co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT`。要求 Android API 21+；已验证的 Kotlin 消费版本为 2.2.10，JVM 11 字节码。构建使用 JDK 17、AGP 8.10.1、Gradle 8.11.1。

```kotlin
val provider = FeatBitProvider.builder(applicationContext) { user ->
    ClientOptions.builder()
        .user(user)
        .sdkKey("YOUR_CLIENT_SDK_KEY")
        .streamingUrl("wss://YOUR_EVALUATION_SERVER")
        .eventsUrl("https://YOUR_EVALUATION_SERVER")
        .build()
}.build().value ?: error("Invalid provider configuration")

// 在协程中注册。检查状态；上游会将初始化失败转换成状态。
OpenFeatureAPI.setProviderAndWait(provider, ImmutableContext("user-123"))
val enabled = OpenFeatureAPI.getClient().getBooleanValue("new-checkout", false)

// 登录或属性改变都通过 OpenFeature 更新，不直接操作底层客户端。
OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("user-456"))

// 关闭准入立即生效；Operation 的完成才表示释放结果已确定。
provider.close().observe { outcome ->
    // 检查 outcome.code，以及成功时的 cleanupComplete / undeliveredEvents。
}
```

端点请使用实际 FeatBit 部署所需的地址。Factory 必须使用传入的 user 构建配置；它只在初始化时调用一次。Provider 独占自己的 SDK client。

匿名身份需同时设置 `identityPolicy(IdentityPolicy.PERSISTENT_ANONYMOUS)` 与 `ClientOptions.anonymousEnabled(true)`。空上下文且无属性表示持久化匿名用户；默认策略要求非空 targetingKey。退出登录可设置 `ImmutableContext()`，不会重置已持久化的匿名 ID。

六类评估均支持：Boolean、String、Int、Long、Double、Object。类型错误交给 OpenFeature 返回调用者原始默认值并触发 error hook。数字沿用 Double 模型：Int/Long 允许精度损失、截断和饱和。Track 的数值转有限 Double，结构属性不发送；每个 provider 最多记录一次忽略结构属性的警告。

Provider 扩展包含 `getConnectionInformation`、`setOffline`、`setOnline`、`flush`、`clearCache`、`close`。模式/缓存操作预算包含排队；提交后的超时不撤销核心操作。`shutdown()` 不等待释放完成，`close()` 返回可观察结果。

可运行的完整接入示例：

- [Kotlin 消费端](consumer-tests/kotlin/src/main/kotlin/co/featbit/consumer/kotlin/SmokeActivity.kt)：真实 OpenFeature Client、六类评估、具名/匿名切换、错误默认值与关闭。
- [Java 消费端](consumer-tests/java/src/main/java/co/featbit/consumer/java/SmokeActivity.java)：Builder/SAM、suspend Continuation 调用、评估和关闭。

这些是使用 TestData 的验收应用，不连接服务器或发送分析事件。

## 构建与验证

配置 JDK 17 和 Android SDK（`ANDROID_HOME` 或 `local.properties`）。

```powershell
.\gradlew.bat spotlessCheck :provider:testDebugUnitTest :provider:lintDebug
.\gradlew.bat :provider:publishReleasePublicationToTestRepository
python tools/acceptance.py
# 可选：在已启动的指定设备上安装并执行两种语言、两种构建变体。
python tools/acceptance.py --device emulator-5554
```

本地发布写入 `build/repository`，没有配置远端上传。消费者是独立 Gradle 工程，通过该仓库读取 provider AAR，核心与 OpenFeature 依赖从 Maven Central 解析。脚本会保留逐步日志，设备检查使用唯一运行标识，避免误认旧日志。

## 文档

- [实现与验证记录](docs/verification.md)
- [设计总览](docs/README.md)
- [详细架构](docs/architecture.md)
- [公共 API](docs/api-design.md)
- [行为规范](docs/behavior-design.md)
- [核心 SDK 接入契约](docs/core-sdk-contract.md)
- [详细验收协议](docs/architecture-validation.md)
- [源码依据与实施验收](docs/implementation-plan.md)
