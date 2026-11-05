# Recording writes no file — what to check

Observed state: App permissions shows **Notifications only** ("No permissions denied"), App info shows Storage usage ~18 MB and it never grows after a recording attempt, while Data usage grows normally (stream is playing).

Conclusion: the app never declared a storage/media permission, and it is not persisting anything. Granting permissions from Settings cannot fix this, because there is nothing to grant. The recorder code must be fixed.

## 1. Manifest

```xml
<!-- targetSdk 33+ : no storage permission is needed for app-private files -->
<!-- Save into MediaStore instead of a public path -->

<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<!-- only for targetSdk <= 32 -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<service
    android:name=".RecordService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

A `MediaProjection` recorder **must** run inside a foreground service declared with `foregroundServiceType="mediaProjection"`, started before `getMediaProjection()` is used on Android 14+. Without it the capture returns a black/empty stream and the file ends up 0 bytes.

## 2. Consent flow

`MediaProjectionManager.createScreenCaptureIntent()` must be launched every time, and the result passed to the service. If the app never shows the "Start recording or casting with <app>?" dialog, it is not using MediaProjection at all — that alone explains why nothing is saved.

## 3. Where the file goes

Write through `MediaStore` so the file appears in Gallery:

```kotlin
val values = ContentValues().apply {
    put(MediaStore.Video.Media.DISPLAY_NAME, "pluto_${System.currentTimeMillis()}.mp4")
    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PlutoIPTV")
    put(MediaStore.Video.Media.IS_PENDING, 1)
}
val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
// ... write to resolver.openOutputStream(uri) ...
values.clear()
values.put(MediaStore.Video.Media.IS_PENDING, 0)
resolver.update(uri, values, null, null)
```

App-private files (`context.getExternalFilesDir(null)`) need no permission but are invisible in Gallery — the in-app Gallery button then shows nothing, which looks identical to "recording failed".

## 4. OEM settings that kill recorders

- Xiaomi / Redmi / POCO (HyperOS, MIUI): Autostart ON, "Display pop-up windows while running in background" ON, Battery saver → No restrictions.
- App info → **Manage app if unused** OFF. When ON, Android strips permissions and deletes temporary files.
- Battery usage → **Unrestricted**.

## 5. How to tell the two failure modes apart

| Observation | Meaning |
| --- | --- |
| Storage usage grows, nothing in Gallery | File is written to app-private storage; fix the MediaStore save or the Gallery listing |
| Storage usage does not change at all | Recorder never runs or never writes: foreground service / MediaProjection / output stream is broken |
| No "Start recording?" dialog appears | App is not using MediaProjection |
| Dialog appears, then a "recording stopped" notification flashes | Foreground service missing the `mediaProjection` type or killed by OEM battery manager |
