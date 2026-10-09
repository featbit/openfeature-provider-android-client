# Sample verification — 2026-10-09

These results belong to the OpenFeature sample implementation in this repository. They do not
inherit the Android SDK sample's historical acceptance record or resolve the upstream G02 limitation.

## Environment and exercised behavior

Windows, JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.10, Android API 34 x86_64 emulator
`Pixel_3a_API_34` (`emulator-5554`). Both apps consume the provider's locally published release AAR,
with Maven Central FeatBit 0.2.0 and OpenFeature Kotlin 0.9.0 dependencies.

- Root Spotless apply/check completed successfully.
- Kotlin: 3 business tests and 2 JSON bridge tests passed.
- Java: 4 business tests and 2 JSON bridge tests passed.
- Both real-provider device flows passed: initial four flags, checkout toggle, out-of-range
  discount, invalid menu, missing-flag fallback/error code, restore, requested OpenFeature user
  context, disabled Local Track, reconnect and connection fallback URL validation.
- Both Debug and R8 Release builds and Debug/Release lint passed. Final full validation completed
  successfully (`build-samples-final.log`, 244 tasks). After the Java event-label-only correction,
  Java Debug/Release assembly and both lint variants passed again (`build-samples-java-label.log`).
- Both final R8 Release APKs were installed and cold-started on the emulator. UI assertions confirmed
  the compact checkout, non-default promotion, 10% discount / $4.50 price, and readable Ready status.
  Final Java history additionally asserted the literal `Ready` and `Flags changed` event labels.
  This last copy-only correction was checked in Release on device, without repeating the full Debug flow.
- Debug lint has 0 errors with 18 Kotlin / 15 Java warnings, primarily inherited native UI
  text/localization/deprecation recommendations. No warning baseline or suppression was added.

## Native UI evidence

Local review captures are intentionally ignored by Git under `.impeccable/review/` at repository root.
Each language has `demo`, `flags`, `detail`, `inspect`, `connection`, `dark`, `font130`, and `tablet`
PNG captures. Phone is 1080×2220 at 440 dpi; tablet layout is emulated using 1600×2560 at 320 dpi;
font scale is 1.3 for the large-text captures. System overrides are restored after capture.

The screenshots were captured after checking that app content had appeared. Independent native
finish review returned **ship**, with no material fixes, for the 16 documented Debug captures.
Additional `release.png` captures show the two Release start screens; Java `release-inspect.png`
records the final minification-safe labels. Concept images are not runtime evidence. The native
implementation does not use the HTML/CSS detector.

OpenFeature status/events use explicit readable labels rather than runtime class names. The design
extraction was reconciled after that correction. UI review is not a claim of complete interactive
accessibility or Live network coverage.

## Limits

No real FeatBit deployment credentials or backend persistence checks were used. Live Streaming,
Polling, timed fallback/recovery, Track delivery, and server-side user targeting remain manual
acceptance items. No physical device, API 21, TalkBack, hardware keyboard, deep sleep or complete
OS/device matrix claim is made. The tablet capture uses an emulator viewport override, not a
separate tablet AVD or physical tablet. CI configuration includes sample builds, tests, lint and
APK artifacts. That sample verification was local; subsequent hosted Verify evidence is recorded in [provider verification](../docs/verification.md#current-scope-and-hosted-verification).
