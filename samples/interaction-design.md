# Interaction contract

Preserve the SDK Café's Demo / Flags / Inspect navigation, 600dp navigation rail, scrollable sheets, system Back, local flag editors, classic/compact checkout, size retention, contextual flag shortcuts, draft retention across rotation and Apply-only connection activation. Shared approved concept images are composition references; native controls keep 48dp touch targets.

Session changes serialize through busy state. Flush prevents reconnect/user/mode changes until settled. Local values only update after TestData completion. Read records become stale after relevant changes. OpenFeature events refresh the four known keys; a diagnostic timer only reads connection information and never evaluates flags. Context reconciliation hides the old business reads until completion; the selected preset then represents the requested OpenFeature context, while evaluation details determine availability.

Flags list values are last OpenFeature evaluations, including fallbacks and error codes. Detail evaluation shows caller default, requested user, time and reason/error. Live values are read-only. No raw all-flags snapshot or core EvaluationDetail is exposed.

Connection preserves Streaming/Polling/fallback behavior. Streaming fallback requires Polling URL only when enabled. Events require Events URL when enabled. Validate before closing the current provider. Credentials stay only in process memory. User switch closes the sheet when the OpenFeature call completes; errors are visible.

Order simulates a coffee purchase. Live with events enabled invokes OpenFeature Track; the result copy explicitly does not acknowledge delivery. Inspect separates provider API status from underlying connection state. Reconnect logs incomplete cleanup or undelivered events from the old provider.
