# AudioBridge — TV system audio → phone → hearing aids

Two Android apps that stream a TV box's **system audio** over your local Wi‑Fi to a phone.
The phone plays it as ordinary media audio, so Android routes it to the paired ASHA
hearing aids exactly like music or a podcast.

* **`sender`** — Android TV app (Mecool KM2 Plus / Google TV, Android 11, API 30). Captures
  playback audio with `MediaProjection` + `AudioPlaybackCaptureConfiguration`, encodes it as
  10 ms PCM frames and sends them over UDP.
* **`receiver`** — phone app (Samsung Galaxy A56, Android 15). Receives UDP, runs an adaptive
  jitter buffer and plays through `AudioTrack` in low-latency performance mode with stream type
  `MEDIA`.

No cloud, no accounts, no ads, no analytics, no internet access required at runtime. Everything
stays on your LAN.

**Arayüz dili / UI language:** the interface is **Turkish by default** — it looks Turkish even
on a TV box or phone whose system language is English. If the device language is English, the
English translation (`values-en`) is used instead. Turkish is the unqualified `values/` resource
set, so it is the fallback for every other locale.

---

## 1. What you get

| File | Purpose |
| --- | --- |
| `sender/build/outputs/apk/debug/sender-debug.apk` | TV app, debug build (installable, debuggable) || `sender/build/outputs/apk/release/sender-release.apk` | TV app, release build (signed with the local debug key) |
| `receiver/build/outputs/apk/debug/receiver-debug.apk` | phone app, debug build |
| `receiver/build/outputs/apk/release/receiver-release.apk` | phone app, release build |

Convenience copies of all four APKs are placed in `dist/`.

Package names (you need them for the adb workaround):

* sender: `com.audiobridge.sender`
* receiver: `com.audiobridge.receiver`

---

## 2. Build

Requirements:

* JDK 17
* Android SDK with platform **API 36** and build-tools (set `sdk.dir` in `local.properties`)
* Gradle 8.14.3 — a wrapper is included, so just use `./gradlew`

```bash
# from the repository root
./gradlew assembleDebug        # both debug APKs
./gradlew assembleRelease      # both release APKs
./gradlew testDebugUnitTest    # protocol / jitter-buffer / reassembly unit tests
```

On Windows:

```bat
gradlew.bat assembleDebug
```

The project is one Gradle build with two modules plus a shared source directory
(`shared/src/main/java/...`) that both modules compile in, so the wire protocol exists exactly
once. Kotlin, no third-party runtime dependencies, no AndroidX — the APKs are ~2.4 MB.

---

## 3. Install

### Receiver (phone)

```bash
adb install -r receiver/build/outputs/apk/debug/receiver-debug.apk
```

Or copy the APK to the phone and tap it (allow "install unknown apps").

First launch asks for the notification permission (Android 13+). Nothing else is needed —
there is no microphone, location or storage permission on the phone.

### Sender (Mecool KM2 Plus)

The Mecool has no touchscreen and usually no Play Store install path, so use adb.

**Over USB**

1. On the TV box enable developer options (Settings → System → About → tap "Build" 7×) and
   turn on **USB debugging**.
2. Connect a USB-A ↔ USB-A (or USB-C, depending on your cable/adapter) cable between the PC
   and the box.
3. `adb devices` must list the box (accept the "Allow USB debugging" prompt on screen with the
   remote).
4. Install:

```bash
adb install -r sender/build/outputs/apk/debug/sender-debug.apk
```

**Over Wi‑Fi (adb tcpip)**

```bash
adb connect <MECOOL_IP>:5555
adb install -r sender/build/outputs/apk/debug/sender-debug.apk
```

The app appears on the Google TV home screen as **AudioBridge Gönderici** (Leanback launcher
entry, with a TV banner).

### The MediaProjection workaround (read this if capture never starts)

Some TV boxes never show Android's screen-capture consent dialog, or silently deny it.
Android requires the `PROJECT_MEDIA` app-op to run a `mediaProjection` foreground service, so
allow it once for the sender package:

```bash
adb shell appops set com.audiobridge.sender PROJECT_MEDIA allow
```

Then reopen the app and press **Start streaming** again. The app shows this command in its
footer and in a dialog when the permission is missing or denied.

The sender does not need root for anything else.

---

## 4. Use it

1. **Phone:** open *AudioBridge Alıcı*, press **Dinle**. It binds UDP port **47811** and
   announces itself on the LAN over mDNS (`_audiobridge._udp`). The screen shows
   `Bu telefon: 192.168.x.y:47811`.
2. **TV box:** open *AudioBridge Gönderici*.
   * Press **Alıcı ara** and pick the phone from the list (mDNS), **or**
   * type the phone's IP shown in the receiver app into **Alıcı adresi**
     (`192.168.x.y`, or `192.168.x.y:47811` if the port differs).
   The last address is remembered.
3. Press **Yayını başlat** and accept the capture prompt.
4. Play something on the TV. Audio should arrive in the hearing aids.

Useful controls:

* **Mono** on the sender halves the bandwidth (960 B/frame instead of 1920 B).
* **Tampon** slider on the receiver (20–500 ms, default 150 ms) trades stability for latency.
  The effective target can rise automatically when packets are lost (up to +120 ms) and decays
  back when the link is stable. The current value is shown in the status card.
* **Dinlerken ekran açık kalsın** keeps the receiver display awake; playback itself continues
  with the screen off thanks to the foreground service, a partial wake lock and a Wi‑Fi lock.
* **Durdur** on either side ends the stream. If the sender disappears, the receiver notices
  after 2 s, flushes the buffer and goes back to "Gönderici bekleniyor…".

---

## 5. Latency and the latency test mode

The tunable part of the total delay is the receiver buffer plus the network. The design target
is **under 300 ms end-to-end excluding Bluetooth**, and on a clean 5 GHz network the measured
path is typically:

```
10 ms  capture frame          (fixed by MediaProjection frame size)
 +     network transit        (usually 2–10 ms on 5 GHz Wi-Fi)
 +     jitter buffer          (150 ms default, 20–500 ms tunable)
 +     AudioTrack low-latency (~20–40 ms)
 +     Bluetooth (ASHA/A2DP)  (not counted, typically 50–150 ms, outside the app's control)
```

To measure the real delay, enable **Gecikme testi: saniyede bir tık sesi** on the sender and
press Start. In this mode the sender generates the click itself (no capture permission is
needed), plays it on the TV's own output **and** sends exactly the same audio over the network.
Every second you hear a click on the TV and then the same click in the hearing aids; the gap
between them is the real added latency of the whole link.

Practical notes:

* Use 5 GHz. 2.4 GHz shares airtime with neighbours and Bluetooth and will cause loss.
* Turn off Wi‑Fi power saving on the phone if the link stutters when the screen is off.
* Both devices must be on the same subnet; guest networks and AP isolation block UDP and mDNS.

---

## 6. Protocol (for debugging)

Raw little-endian PCM, 48 kHz, 16-bit, stereo. A **10 ms frame** = 480 samples/channel =
1920 B stereo (960 B mono). Each frame is split into ≤1200 B chunks so no datagram ever needs
IP fragmentation. 20-byte header per datagram:

```
 0  uint16 magic        0xAB01
 2  uint8  version      1
 3  uint8  flags        bit0 mono, bit1 latency-test click
 4  uint32 streamId     random per session; a change resets the receiver buffer
 8  uint32 sequence     frame counter (+1 per 10 ms)
12  uint32 timestampMs  sender monotonic clock
16  uint8  chunkIndex
17  uint8  chunkCount
18  uint16 frameBytes
20  ...    PCM chunk
```

Stereo bitrate ≈ **1.54 Mbit/s**, mono ≈ 0.77 Mbit/s.

The receiver reassembles frames (a lost chunk loses one 10 ms frame, not more), plays them out
on a fixed 10 ms cadence anchored to the first frame, and conceals loss by repeating the last
good frame for up to 3 frames and then by silence.

Diagnostics:

```bash
adb logcat -s AudioBridgeReceiver   # buffer/target/jitter/loss every 5 s, stream changes
adb logcat -s AudioBridgeSender     # session start/stop, capture errors
adb logcat -s AudioBridgeCapture    # MediaProjection capture problems, silence detection
```

---

## 7. Known limits / what to expect on real hardware

* **DRM apps are silent by design.** Netflix, Disney+, Prime Video and similar set
  `ALLOW_CAPTURE_BY_NONE`; Android will not let any app capture them. The sender detects 10 s of
  digital silence and shows a hint. YouTube, most music/streaming apps, IPTV players, Kodi and
  games are captured normally.
* Apps must be playing through the normal media path (`USAGE_MEDIA`, `USAGE_GAME`,
  `USAGE_UNKNOWN`). Notification sounds and calls are not captured.
* A few TV boxes only start capture when the app is in the foreground; keep the sender window
  open while starting.
* The phone must have the hearing aids connected in Bluetooth settings; the app only uses
  normal media playback and does not talk to the aids directly.
* Audio is unencrypted UDP on the LAN. Do not use it on a network you do not trust.

---

## 8. Project layout

```
settings.gradle.kts, build.gradle.kts, gradle.properties, local.properties
gradlew, gradlew.bat, gradle/wrapper/…
shared/src/main/java/com/audiobridge/protocol/   # wire format, jitter buffer, PCM, click track
sender/src/main/res/values/       # Turkish strings (default for every locale)
sender/src/main/res/values-en/    # English strings
sender/   AndroidManifest, MainActivity (TV UI), StreamService (FGS + MediaProjection),
          AudioCapture, ClickStream, UdpSender, NsdDiscovery, SenderState, Prefs
receiver/ AndroidManifest, MainActivity (phone UI), ReceiveService (FGS + UDP + jitter playout),
          AudioOutput, NsdAdvertiser, ReceiverState, Prefs
receiver/src/main/res/values/     # Turkish strings
receiver/src/main/res/values-en/  # English strings
sender/src/test, receiver/src/test               # JVM unit tests (37 tests)
tools/make_app_icons.py                          # regenerates launcher icons and TV banner
```

### Tests

`./gradlew testDebugUnitTest` runs 37 JVM tests covering: header encode/decode (including
uint32 sequence wraparound, malformed/truncated datagrams, chunk-count validation), frame
reassembly (chunk loss, duplicates, conflicting metadata, bounding), the jitter buffer
(anchor timing, 10 ms cadence, concealment, late drops, resync after >1 s gaps, adaptive
target growth and decay, clamping, statistics) and PCM helpers plus the click generator.
