# Integration map

The two applications implement the same workflow independently. Start with `SampleSession.kt`
or `SampleSession.java`; UI code lives in `MainActivity`, `CafeForms`, `CafeViews` and `CafeModel`.
Shared fixtures and presentation resources live in `shared/`.

1. Application creates one session; Activity starts it after its first frame.
2. `connect` validates the draft, captures the submitted configuration, closes the previous
   provider, creates `FeatBitProvider`, and calls OpenFeature `setProviderAndWait` with a preset context.
3. `refresh` evaluates the four known flags through the OpenFeature Client. Business validation
   separately rejects discounts outside 0–100 and invalid menu shapes.
4. Client events invalidate earlier detail reads and refresh business values. A one-second timer
   refreshes only provider connection diagnostics, with no evaluation or network calls.
5. `identify` calls OpenFeature `setEvaluationContextAndWait`. The preset name describes the
   requested OpenFeature context; errors remain visible in evaluations.
6. `order` invokes OpenFeature Track only in Live with events enabled. `flush`, mode changes
   and close use the public provider extension operations.

`OpenFeatureJson` maps JSON recursively to OpenFeature Value without reflection or string-valued
object evaluation. Java's `OpenFeatureBridge` adapts suspend completions and Flow observations
using Continuation and a cancellable Job on the main dispatcher. Core SDK Operation observation
is retained only for provider extensions and TestData edits.

Use the root formatter, then the commands in [acceptance](setup-and-acceptance.md). The samples
consume an actual locally published AAR, not a Gradle project dependency or SDK implementation source.
