# Android UI notes (Compose + Material 3 Expressive)

Replaces the programmatic-View UI. Telemetry and behaviour are unchanged: `TaskStore`, `TaskApi`, `Faults`,
`Autopilot`, `HangWatchdog`, `ExitInfoReporter`, the three `AppTelemetry` flavors and the launch-args contract
are untouched. Only `MainActivity.kt` changed (now a `ComponentActivity`) plus the new `ui/` package.

## Versions
| | |
|---|---|
| Compose BOM | 2026.09.00 (foundation 1.12.1, icons-core 1.7.8) |
| `androidx.compose.material3:material3` | **1.5.0-beta01** (pinned above the BOM's 1.4.0; latest on Google Maven) |
| `androidx.activity:activity-compose` | 1.13.0 |
| Kotlin Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` 2.4.21 |
| AGP / Gradle | 9.4.1 / 9.8.0 (unchanged; AGP's built-in Kotlin) |
| `compileSdk` | 37 (was 36; Compose 1.12 / material3 1.5 need it). `minSdk` 26, `targetSdk` 36 unchanged |

## What is used
- `MaterialExpressiveTheme` + `MotionScheme.expressive()`; dynamic color on Android 12+, a hand-picked teal/amber
  light and dark palette below that (`ui/Theme.kt`). Emphasized typography (`displayLargeEmphasized` title,
  `titleMediumEmphasized`, `headlineSmallEmphasized`).
- `AppBarWithSearch` + `ExpandedFullScreenSearchBar` (search calls `store.search`, same `task.search` action).
- `SegmentedListItem` + `ListItemDefaults.segmentedShapes`, `SwipeToDismissBox` (right = complete, left = delete,
  always springs back; the list updates when the store call finishes).
- `ToggleButton` with `ButtonGroupDefaults.connected*ButtonShapes()` as the All / Open / Done filter.
- `HorizontalFloatingToolbar` (Sync, Stats, Debug) with `FloatingToolbarDefaults.VibrantFloatingActionButton` (Add);
  collapses when scrolling down.
- `LoadingIndicator` while any store call is in flight, `LinearWavyProgressIndicator` in the Stats card,
  `MaterialShapes.Cookie9Sided` badge for completed tasks and the empty state, `ModalBottomSheet` for Add and Debug,
  `Snackbar` on failed actions. Edge-to-edge through `enableEdgeToEdge()` (status bar icons follow light/dark).
- The mode (bare/otel/edot) is a chip above the title.

## Experimental
Everything expressive is behind `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`; `AppBarWithSearch`,
`ExpandedFullScreenSearchBar`, `SegmentedListItem` and the floating toolbar are `ExperimentalMaterial3Api` /
`ExpressiveApi` in 1.5.0-beta01 and can change before stable. `material-icons-core` only (no extended set), so
Stats/Debug use the Info/Build icons.

## Telemetry-preserving details
- Stats still runs `AppTelemetry.action("screen.view", screen=stats) { Faults.computeStats(...) }` on the main thread,
  so the 8 s freeze fault (and ANR) happens inside the same span. Verified: ANR kill, then `app.anr_previous_run` next launch.
- `app.launch` wraps `setContent`; `screen.view(list)`, `app.foreground`/`app.background` unchanged.
- Build isolation re-verified after adding Compose: bare 0 `io/opentelemetry` classes, otel has the upstream OpenTelemetry Android agent and no EDOT agent
  (only the 2-reference generated config stub, as before). Debug APKs are now ~13-14 MB each (Compose is in all three
  flavors, so the per-flavor differences are unchanged).

## Emulator gotcha
On a fresh emulator the Pixel keyboard shows a one-time "Try out your stylus" sheet when a text field first gets focus.
`adb shell settings put secure stylus_handwriting_enabled 0` suppresses it.
