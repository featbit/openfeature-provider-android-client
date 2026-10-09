# FeatBit Café product authority

Audience: developers evaluating FeatBit through OpenFeature. A simulated coffee purchase makes
feature-flag changes visible; Flags and Inspect explain OpenFeature evaluations and provider diagnostics.

The approved product contract is [README.md](./README.md),
[interaction-design.md](./interaction-design.md), and [setup-and-acceptance.md](./setup-and-acceptance.md).
The approved compositions are `ui-overview-v2.png`, `ui-checkout.png`,
`ui-connection-users.png`, `ui-flag-editing.png`, and `ui-connection-fallback-proposal.png`.
This file does not replace those contracts. The implemented Kotlin and Java apps share this authority.

## Implementation direction

- Audience and task: working credential-free Local demo, then real Live configuration and SDK inspection.
- Layout: Demo / Flags / Inspect, native sheets and Back; 600dp+ uses a navigation rail.
- Visual language: warm neutral surfaces, espresso actions, amber warnings, restrained green success,
  Roboto, real coffee photograph. English UI text. No default lavender component surfaces.
- First viewport: business hero and compact checkout; Flags lists cached evaluations, with evaluation in flag details;
  Inspect groups connection, events, and recent activity. Large text remains scrollable.
- Interaction: boolean changes checkout layout while preserving selected size and amount;
  editable Local flags remain distinct from remote Live evaluations. OpenFeature results and provider extension Outcomes stay truthful.

The app displays the approved overview's photo region directly from the unchanged reference
asset using Android BitmapRegionDecoder; it does not substitute a new latte photograph.
Current native capture coverage is recorded in [VERIFICATION.md](VERIFICATION.md).
Generated concepts are not measurement-grade Android screenshots: native accessibility targets,
font scaling, system bars, sheet topology from the written contract, and tablet rail remain
explicit platform adaptations. No unsupported claim of pixel-exact rendering on every device.

Classic keeps its order action and simulation helper above navigation while its longer price/size
content scrolls. This preserves the written 48dp touch-target requirement: conceptual compact
radio spacing cannot accommodate those targets and the full-width photo in one phone viewport.

## Implementation ownership

Both app builds consume `shared/res` and `shared/assets` for layouts, palette, themes, strings,
icons, the approved photo, flag fixtures, and users. The native view construction and interaction
wiring live in `MainActivity`, `CafeForms`, and `CafeViews` under
[Kotlin sources](./kotlin/src/main/kotlin/co/featbit/sample/kotlin/) and
[Java sources](./java/src/main/java/co/featbit/sample/java/). Each language implements its own
`SampleSession` and `CafeModel`; the product behavior and design contract remain shared.

## Provider adaptation

This copy preserves the SDK sample visual world. API-specific behavior is defined in this repository's README and interaction-design. It makes no claim to the original SDK sample verification history.
