# 🎙 AudioRecorder — Android App

Continuous interval recorder that automatically splits audio into timed segments.
Runs fully in the background via a Foreground Service.

---

## Features

| Feature | Detail |
|---|---|
| **Auto-split** | Splits recording every 5 / 10 / 15 / 20 / 30 / 45 / 60 min |
| **Audio quality** | Low (32 kbps), Medium (96 kbps), High (192 kbps) |
| **Channels** | Mono or Stereo |
| **Filename prefix** | Customisable (alphanumeric, `-`, `_`) |
| **Output format** | M4A / AAC — universally playable |
| **Background** | Foreground Service + WakeLock keeps recording with screen off |
| **Notification** | Shows segment count & interval; tap to open app, tap "Stop" to end |
| **Android support** | API 23 (Android 6.0) → API 34 (Android 14) |

---

## File Storage

| Android version | Location |
|---|---|
| Android 10+ (API 29+) | `Android/data/com.example.audiorecorder/files/Music/AudioRecorder/` |
| Android 9 and below | `Music/AudioRecorder/` (public Music folder) |

Files are named: `<Prefix>_YYYY-MM-DD_HH-mm-ss.m4a`

---

## Building

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17+
- Android SDK 34

### Steps

1. Clone / open the project in Android Studio
2. Let Gradle sync
3. Connect a physical device (recommended — microphone may not work on emulator)
4. Run → **app**

To build a release APK:
```
./gradlew assembleRelease
```

---

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone access |
| `FOREGROUND_SERVICE` | Keep service alive |
| `FOREGROUND_SERVICE_MICROPHONE` | Required on Android 14+ for mic in FG service |
| `WAKE_LOCK` | Prevent CPU sleep mid-recording |
| `WRITE_EXTERNAL_STORAGE` | Save files on Android 9 and below only |

---

## Architecture

```
MainActivity
  │  Sends start/stop intents
  │  Receives broadcast updates (status, file name, segment count)
  │
  └──▶ RecordingService (Foreground Service)
         │  MediaRecorder — captures mic audio to M4A
         │  Handler.postDelayed() — triggers splits at the set interval
         │  WakeLock — keeps CPU running in background
         └──▶ Files → Music/AudioRecorder/
```

---

## Notes

- **MP3 is not natively supported** by Android's `MediaRecorder`. M4A (AAC) is used instead —
  it has equal or better quality at the same bitrate and plays on all devices and platforms.
- If you want true MP3 output, integrate the [LAME library via JNI](https://github.com/intervigilium/liblame).
- On Android 10+, files are in `Android/data/...` — navigate there with a file manager like
  *Files by Google* or *Solid Explorer*.
