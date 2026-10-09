# FeatBit Café through OpenFeature

Two independent Android apps reproduce the FeatBit Android SDK Café sample: Kotlin and pure Java source, with shared native Views resources and the same coffee-ordering demo. They evaluate and track exclusively through an OpenFeature Client backed by FeatBitProvider.

## Build and run

Use JDK 17 and Android SDK 34. Set `JAVA_HOME` and `ANDROID_HOME` (or configure Android SDK location for the samples build). Run from the repository root:

```powershell
.\gradlew.bat :provider:publishReleasePublicationToTestRepository
.\gradlew.bat -p samples :kotlin:assembleDebug :java:assembleDebug
.\gradlew.bat -p samples :kotlin:installDebug
# Or install the independent Java app:
.\gradlew.bat -p samples :java:installDebug
```

You can also open `samples/` directly in Android Studio; it includes the same Gradle 8.11.1 wrapper as the provider root. Publish the provider locally before the first sample sync.

Open **OpenFeature Café Kotlin** or **OpenFeature Café Java**. Application IDs are `co.featbit.openfeature.sample.kotlin` and `co.featbit.openfeature.sample.java`, so they coexist with the direct SDK samples. Local Demo starts without credentials or a server. Both apps consume the locally published provider AAR from `../build/repository`; its transitive dependencies are FeatBit 0.2.0 and OpenFeature Kotlin 0.9.0. Kotlin uses 2.2.10, AGP 8.10.1 and the root Gradle 8.11.1 wrapper.

## Try it

- **Demo:** change coffee size, see the flag-controlled layout/message/discount/menu, create a simulated order, and open each flag from its shortcut.
- **Flags:** browse the four known demo keys, evaluate a flag, edit/remove/restore Local values and inspect defaults/errors. Local TestData does not simulate targeting.
- **Inspect:** switch users through OpenFeature context, inspect connection state, toggle offline/online, flush pending events and view recent activity.
- **Connection:** apply Live Streaming or Polling with your client SDK key and service URLs. Streaming optionally falls back to Polling; the toggle defaults off. Invalid drafts do not retire the active provider. Credentials and drafts are held in process memory only.

Live flags must use the keys/types in [demo-flags.json](shared/assets/demo-flags.json). Preset context attributes come from [users.json](shared/assets/users.json). For an emulator, a service on the host can use `10.0.2.2`; for USB devices use `adb reverse` and localhost, or a reachable LAN address. These demo apps allow HTTP/WS in Debug and Release; choose HTTPS/WSS for your deployment. Release APKs are minified and signed with the local debug key for sample installation.

## API boundaries

| Action | API used |
| --- | --- |
| Create configuration | `FeatBitProvider.builder`, factory uses its supplied user |
| Register provider | `OpenFeatureAPI.setProviderAndWait` |
| Switch user and attributes | `setEvaluationContextAndWait(ImmutableContext(...))` |
| Boolean, string, number, JSON | OpenFeature `getBooleanDetails`, `getStringDetails`, `getDoubleDetails`, `getObjectDetails` |
| Custom order event | OpenFeature `track` with `TrackingEventDetails` |
| Flag/status notifications | OpenFeature Client `observe` |
| Offline/online, flush, close, diagnostics | FeatBitProvider public extensions |
| Edit Local fixtures | FeatBit TestData public testing API |

No sample creates a FeatBitClient or evaluates directly through the core SDK. Each application owns one process-lifetime session; rotation preserves the session. Reconnect cancels observers, awaits the old provider's close result, and registers a replacement. Java uses a small Java-only suspend/Flow bridge; it does not depend on Kotlin sample code.

OpenFeature does not expose `allVariations`: the Flags list caches the latest typed evaluations of the four known keys. Rendering the list does not perform additional evaluations, but connection/flag/context refreshes evaluate these keys and can emit evaluation events in Live. JSON is converted to/from typed OpenFeature Value trees, not evaluated as a string.

Successful FeatBit provider evaluations currently report OpenFeature reason `UNKNOWN`; missing/type/readiness failures retain the caller default and expose an OpenFeature error code. Do not equate UNKNOWN with fallback. The detail view shows the real reason/error code.

Track returns no receipt. The UI says **Track invoked · delivery not acknowledged**, and does not claim acceptance or server persistence. Local/disabled events do not call Track. Flush displays the provider extension result. Neither a completed Track call nor a successful transport flush proves database persistence.

The selected user is the requested OpenFeature context. Context reconciliation can finish with an error state; it is not an identity-adoption receipt. Evaluations remain subject to provider admission. OpenFeature 0.9.0 has the known G02 lifecycle status issue documented in [provider verification](../docs/verification.md); Inspect displays OpenFeature status separately from FeatBit connection diagnostics. These samples do not correct that upstream limitation.

## Design and verification

The original SDK sample source baseline is `dc351c6aa58c39fa594968e17ee6a5a2bde9049b`. Shared artwork, resources and native layout are reused, with API-specific labels adapted. See [design](DESIGN.md), [interaction contract](interaction-design.md), [acceptance](setup-and-acceptance.md), and [this run's verification](VERIFICATION.md). Original SDK verification claims are not inherited.
