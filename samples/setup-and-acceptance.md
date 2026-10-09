# Setup and acceptance

Follow [README](README.md) for build, installation and endpoints. Run from repository root:

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat -p samples :kotlin:testDebugUnitTest :java:testDebugUnitTest :kotlin:lintDebug :java:lintDebug :kotlin:assembleRelease :java:assembleRelease
.\gradlew.bat -p samples :kotlin:connectedDebugAndroidTest :java:connectedDebugAndroidTest
```

Device tests use the real published provider AAR and public OpenFeature client. Verify initial four values, updates, defaults/error codes after removing a flag, restoration, context switching, repeated reconnect, disabled tracking and form validation. Business unit tests cover discount bounds/rounding and malformed menu configuration. JSON bridge tests cover nested objects, arrays, primitives and null.

Visual acceptance uses an API 34 emulator: Demo, Flags, detail, Inspect, connection, dark mode, larger text and tablet width. Historical SDK screenshots are references, not test evidence. Keep actual results and unexecuted checks in [VERIFICATION](VERIFICATION.md).

For Live acceptance, configure a real deployment, confirm targeting for preset user attributes, update all four flag types remotely, toggle offline/online, check Streaming fallback/recovery, create an order and flush. Confirm event receipt at your backend separately. Real credentials, network failure injection, physical devices and server persistence are not implied by Local tests.
