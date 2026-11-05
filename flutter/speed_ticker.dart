// Live "KB/S" badge for a Flutter player screen.
//
// Why the badge freezes in most builds: the number is produced once by a
// FutureBuilder / first-buffer callback, or it is assigned to a local variable
// instead of a ValueNotifier, so nothing rebuilds the label.
//
// video_player and better_player do not expose byte counters, so we sample
// Android's TrafficStats over a method channel. Delta based: bytes in the last
// second / 1024.

import 'dart:async';
import 'package:flutter/services.dart';

class SpeedMeter {
  SpeedMeter({this.channelName = 'speed'});

  final String channelName;
  late final MethodChannel _channel = MethodChannel(channelName);

  /// Bind this to the label with ValueListenableBuilder.
  final ValueNotifier<String> label = ValueNotifier<String>('0 KB/S');

  Timer? _ticker;
  int? _lastRx;
  bool _playing = false;

  void setPlaying(bool playing) {
    _playing = playing;
    if (playing) {
      start();
    } else {
      label.value = '0 KB/S';
    }
  }

  void start() {
    _ticker?.cancel();
    _lastRx = null;
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) async {
      if (!_playing) return;
      final int? rx = await _channel.invokeMethod<int>('rxBytes');
      if (rx == null) return;
      final int delta = _lastRx == null ? 0 : (rx - _lastRx!).clamp(0, 1 << 40);
      _lastRx = rx;
      final int kb = (delta / 1024).round();
      label.value = '$kb KB/S'; // ValueNotifier -> widget rebuilds every tick
    });
  }

  void dispose() {
    _ticker?.cancel();
    _ticker = null;
    _lastRx = null;
    label.dispose();
  }
}

// ---------------------------------------------------------------------------
// Android side, put inside MainActivity.configureFlutterEngine(engine):
//
// import android.net.TrafficStats
// import io.flutter.plugin.common.MethodChannel
//
// MethodChannel(engine.dartExecutor.binaryMessenger, "speed")
//     .setMethodCallHandler { call, result ->
//         when (call.method) {
//             "rxBytes" -> result.success(TrafficStats.getTotalRxBytes())
//             else -> result.notImplemented()
//         }
//     }
//
// Widget:
//
// ValueListenableBuilder<String>(
//   valueListenable: meter.label,
//   builder: (_, value, __) => Text(
//     value,
//     style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
//   ),
// )
//
// Call meter.setPlaying(true) when playback starts, setPlaying(false) on pause,
// and meter.dispose() in State.dispose(). Never wrap this in FutureBuilder.
