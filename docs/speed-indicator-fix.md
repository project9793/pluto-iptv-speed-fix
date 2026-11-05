# Speed Indicator Fix — Pluto IPTV style overlay

Symptom: the "127 KB/S" badge appears once when playback starts and then never changes.
Cause is almost always one of these four:

1. **Value is set once.** Speed is read in the player's `onStart` / first-buffer callback or inside a `FutureBuilder`, and nothing reads it again.
2. **Timer exists but UI is not refreshed.** The number is updated in a variable but `setState` / `ValueNotifier` / `notifyListeners` is never called, or the widget rebuilds from a cached copy.
3. **Timer dies on channel change / pause.** The ticker is started in `initState` only, and cancelled on pause or on switching a channel, and never restarted.
4. **Wrong metric.** Speed is computed from *total bytes downloaded so far* instead of the **delta in the last 1 second**, so it freezes at the first non-zero value.

Rule to follow everywhere: sample a counter every 1000 ms, subtract the previous sample, divide by elapsed seconds, update the label, keep the previous sample. If nothing downloaded in the last second, show `0 KB/S` (do not keep the old number).

---

## A. Android native (Kotlin, ExoPlayer / Media3)

Best source: the player's own bandwidth meter — no extra permissions, reflects real stream throughput.

```kotlin
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import android.os.Handler
import android.os.Looper

private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

private val player = ExoPlayer.Builder(context)
    .setBandwidthMeter(bandwidthMeter)
    .build()

private val ui = Handler(Looper.getMainLooper())
private val speedTick = object : Runnable {
    override fun run() {
        // bitrateEstimate is in bits/second, -1 when unknown
        val bits = bandwidthMeter.bitrateEstimate
        val kbPerSec = if (bits > 0) bits / 8 / 1024 else 0
        speedLabel.text = "$kbPerSec KB/S"
        ui.postDelayed(this, 1_000L)
    }
}

// start when the screen becomes visible
override fun onStart() {
    super.onStart()
    ui.removeCallbacks(speedTick)
    ui.post(speedTick)
}

// ALWAYS stop, otherwise you leak a ticker per channel switch
override fun onStop() {
    ui.removeCallbacks(speedTick)
    super.onStop()
}

override fun onDestroy() {
    ui.removeCallbacks(speedTick)
    player.release()
    super.onDestroy()
}
```

If the player is not ExoPlayer, use system counters (delta-based, works for any player):

```kotlin
private var lastRx = TrafficStats.getTotalRxBytes()

val kbPerSec = ((TrafficStats.getTotalRxBytes() - lastRx) / 1024).coerceAtLeast(0)
lastRx = TrafficStats.getTotalRxBytes()
speedLabel.text = "$kbPerSec KB/S"
```

(Total device traffic, so it includes other apps' downloads — acceptable for an approximate badge, but ExoPlayer's meter is more accurate.)

---

## B. Flutter

`video_player` / `better_player` do not expose byte counters, so sample Android's counters over a method channel.

Android side (`MainActivity.kt`):

```kotlin
MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "speed")
    .setMethodCallHandler { call, result ->
        if (call.method == "rxBytes") {
            result.success(TrafficStats.getTotalRxBytes())
        } else result.notImplemented()
    }
```

Dart side:

```dart
final _speedChannel = const MethodChannel('speed');
final speed = ValueNotifier<String>('0 KB/S');
Timer? _ticker;
int? _lastRx;

void startSpeedTicker() {
  _ticker?.cancel();
  _ticker = Timer.periodic(const Duration(seconds: 1), (t) async {
    final rx = await _speedChannel.invokeMethod<int>('rxBytes');
    if (rx == null) return;
    final delta = _lastRx == null ? 0 : rx - _lastRx!;
    _lastRx = rx;
    speed.value = '${(delta / 1024).clamp(0, 99999)} KB/S';
  });
}

void stopSpeedTicker() {
  _ticker?.cancel();
  _ticker = null;
  _lastRx = null;
}
```

Widget:

```dart
ValueListenableBuilder<String>(
  valueListenable: speed,
  builder: (_, v, __) => Text(v, style: const TextStyle(color: Colors.white)),
)
```

Call `startSpeedTicker()` when playback starts and `stopSpeedTicker()` in `dispose()` and on channel switch. Never wrap the label in a `FutureBuilder` — that builds once and freezes exactly like your screenshot.

---

## C. Web / HTML5 player (hls.js)

```js
let lastLoaded = 0, lastTime = performance.now();

hls.on(Hls.Events.FRAG_LOADED, (e, data) => {
  const now = performance.now();
  const loaded = data.frag.stats.loaded;          // bytes for this fragment
  const secs = (now - lastTime) / 1000;
  const kbps = Math.round(loaded / 1024 / Math.max(secs, 0.001));
  lastLoaded = loaded; lastTime = now;
  badge.textContent = kbps + ' KB/S';
});
```

Simplest and most stable alternative: `Math.round(hls.bandwidthEstimate / 8 / 1024)` on a `setInterval(..., 1000)`.

---

## Acceptance checklist

- Value changes within 1–2 seconds of playback and keeps moving while video plays.
- Paused / buffering-stalled stream shows `0 KB/S`, not the last number.
- Switching a channel resets the label and the ticker (no double timers, no stale value).
- Leaving the player screen stops the ticker (`onStop` / `dispose`), so CPU and battery are not burnt in the background.
- Recording + speed overlay both running: the recorder writes on its own thread; the ticker must stay on the main thread only for the label update.
