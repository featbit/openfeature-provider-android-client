package co.featbit.openfeature.android

import co.featbit.android.api.ClientOptions
import co.featbit.android.api.Outcome
import co.featbit.android.api.User

/** Invoked once during initialization. Return options with exactly the supplied initial user. */
public fun interface FeatBitOptionsFactory {
    public fun create(user: User?): Outcome<ClientOptions>
}
