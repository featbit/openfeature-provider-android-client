package co.featbit.openfeature.android

/** Controls whether an empty context may use the SDK's persisted anonymous identity. */
public enum class IdentityPolicy {
    REQUIRE_TARGETING_KEY,
    PERSISTENT_ANONYMOUS,
}
