# Pluto IPTV style player — speed badge & recording fixes

Two bugs seen in a Pluto IPTV style streaming app (Android, v3.7):

1. **Speed badge freezes.** The `127 KB/S` label appears once when playback starts and never changes again.
2. **Recording does not produce a file.** Tapping Record does nothing visible, and App info → Storage usage stays at ~18 MB, i.e. nothing is ever written.

## 1. Speed badge that does not update

Root cause is one of these, in order of likelihood:

- the value is read once in `onStart` / first-buffer callback / a `FutureBuilder`, and never again;
- a ticker exists but the UI is not refreshed (`setState` / `ValueNotifier` / listener missing);
- the ticker is cancelled on pause or channel change and never restarted;
- the number is computed from *total bytes so far* instead of the **delta of the last one second**, so it locks at the first non-zero value.

The rule: every 1000 ms take a counter, subtract the previous sample, divide by elapsed seconds, update the label, keep the sample. Show `0 KB/S` when nothing moved in the last second.

| File | What it is |
| --- | --- |
| [`docs/speed-indicator-fix.md`](docs/speed-indicator-fix.md) | Full write-up: causes, per-platform approach, acceptance checklist |
| [`android/SpeedOverlay.kt`](android/SpeedOverlay.kt) | Drop-in Kotlin implementation using ExoPlayer's `DefaultBandwidthMeter` with a `TrafficStats` fallback |
| [`flutter/speed_ticker.dart`](flutter/speed_ticker.dart) | Flutter version: `MethodChannel` + `Timer.periodic` + `ValueNotifier` |

Acceptance: value changes within 1–2 s, shows 0 when the stream stalls, resets on channel switch, and the ticker stops in `onStop` / `dispose` (no leak, no battery drain in background).

## 2. Recording writes no file

`App permissions` for the app lists **Notifications only** — the app never declared a storage or media permission, so there is nothing to grant from Settings. The permission route therefore cannot fix it; the recorder itself has to save somewhere.

See [`android/RecordingNotes.md`](android/RecordingNotes.md) for the manifest entries, the `MediaProjection` consent flow, the mandatory foreground service with `android:foregroundServiceType="mediaProjection"`, the `MediaStore` insert, and the OEM settings (Xiaomi/HyperOS autostart, background pop-up, battery unrestricted) that silently kill recorders.

## Quick end-user checks (no code)

1. App info → **Manage app if unused** → OFF (it strips permissions and deletes temp files).
2. Battery usage → **Unrestricted**.
3. Other permissions → **Display pop-up windows while running in background** → ON.
4. Record for ~30 s, stop, then re-open App info: if **Storage usage** did not grow, the app is not writing at all — that is an app bug, not a settings problem.
