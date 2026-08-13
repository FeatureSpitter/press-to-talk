# press-to-talk for Android

Fully offline speech-to-text on the phone: record, get a transcript, copy it.
No network, no cloud, no account. Whisper runs on the device's CPU via
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

Companion to the Linux desktop app in the parent directory. No code is shared —
the desktop uses `faster-whisper` (CTranslate2), which has no Android build — but
the audio contract, decode settings and transcript formatting are ports of
decisions already proven there.

## Status

Milestone 1 complete: the project builds and its pure logic is under test.
Recording and recognition are not wired up yet.

## Requirements

- JDK 17 or newer (the build uses a Java 17 target)
- Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`
- `adb`, plus a phone with USB debugging enabled
- ~2 GB of disk for the models

`local.properties` points the build at the SDK. It is not committed; create it
with `sdk.dir=/path/to/Android/Sdk` if it is missing.

## Setup

```bash
scripts/fetch-deps.sh          # sherpa-onnx AAR + VAD + tiny, base, small models
```

Nothing this fetches is committed: the AAR is 47 MB and the models total ~611 MB.
Downloads resume if interrupted. `.cache/` holds the source tarballs and can be
deleted once `.models/` is populated.

To fetch a single model instead of all three: `scripts/fetch-deps.sh small`.

## Build

```bash
./gradlew assembleDevDebug      # lean APK, model pushed separately
./gradlew testDevDebugUnitTest  # JVM tests, no device needed
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

### Flavors

| Flavor | Application ID | Model comes from | Purpose |
|---|---|---|---|
| `dev` | `com.presstotalk.mobile.dev` | pushed to the device once | fast rebuild/install cycle |
| `bundled` | `com.presstotalk.mobile` | `src/bundled/assets/` | self-contained build for sharing |

Different application IDs, so both can be installed side by side. The `dev`
flavor exists because the bundled APK carries a ~375 MB model, and pushing that
over USB on every code change is unbearable.

## Building the shareable APK

```bash
scripts/stage-bundled.sh small        # copy the model into the bundled flavor
./gradlew assembleBundledRelease
# -> app/build/outputs/apk/bundled/release/app-bundled-release.apk  (~413 MB)
```

Self-contained: no push step, no network, works offline from first launch. On
first run it extracts the model out of the APK into internal storage (~0.5 s),
which is also why it loads faster than the dev build.

Signing uses the keystore referenced by `pttStoreFile` in `local.properties`.
If those properties are absent the build falls back to the debug key, which
still installs but cannot upgrade an existing properly-signed install.
**Keep the keystore safe** — losing it means anyone with the app must uninstall
before they can update.

Recipients need to allow installing from unknown sources; 413 MB is too big for
most messaging apps, so send a Drive/Files link.

## Connecting the phone

1. Settings → About phone → tap **Build number** seven times
2. Settings → System → Developer options → enable **USB debugging**
3. Plug in over USB, run `adb devices`, and accept the RSA prompt on the phone
   (tick "Always allow from this computer")

`adb devices` should list the device as `device`, not `unauthorized` or
`no permissions`. If it says `no permissions`, udev rules are missing — install
your distro's `android-sdk-platform-tools-common` (Debian/Ubuntu) or
`android-udev` (Arch/Fedora) package.

## Design notes

### Whisper cannot see past 30 seconds

sherpa-onnx does not work around this. It silently truncates and logs a warning
(`offline-recognizer-whisper-impl.h`), so a three-minute recording would return
only its first ~29.5 seconds.

Silero VAD therefore splits the microphone stream into utterances at natural
pauses, and each utterance is recognized separately. Cuts land in silence, so
they never fall mid-word. `maxSpeechDuration` is a backstop for speech with no
pause at all; it raises the VAD threshold rather than cutting blindly, so even
then the cut lands at the quietest nearby moment.

This also makes the transcript appear live while you speak, at no cost to
quality — the same computation, shown sooner.

### Traps worth knowing

- `OfflineWhisperModelConfig.language` defaults to `"en"` in the Kotlin API, not
  `""`. Left alone it forces English onto Portuguese audio. Use `""` for
  Whisper's own language detection.
- An invalid language string calls `SHERPA_ONNX_EXIT(-1)`, which kills the
  process rather than throwing. Validate before passing it in.
- `vad.flush()` must be called when recording stops, or the final utterance is
  stranded in the VAD buffer and lost. The official `SherpaOnnxVadAsr` example
  omits this.
- Load models from a filesystem path, not from assets. The asset path reads the
  whole model into a heap buffer *on top of* ONNX Runtime's own copy.
- Whisper on Android is CPU-only. There is no GPU backend, and NNAPI is
  deprecated and a poor fit for Whisper's dynamic-shape decoder.

### Model sizes (int8, as downloaded)

| Model | Encoder | Decoder | Total |
|---|---|---|---|
| tiny | 12.9 MB | 89.9 MB | ~104 MB |
| base | 29.1 MB | 130.7 MB | ~161 MB |
| small | 112.4 MB | 262.2 MB | ~375 MB |

The decoder dominates because Whisper's 51,865-token vocabulary quantizes
poorly — the "39M params means 39 MB" intuition does not hold.

## Benchmarks

No public Whisper-on-Android numbers exist, so the model choice was made from
measurements on the target device rather than extrapolation.

**Pixel 8 Pro (Tensor G3, 9 cores, 11.5 GB), Android 17 / API 37, 4 threads, CPU,
int8 models, greedy decoding.** Two LibriSpeech clips (6.6 s and 16.7 s), first
decode discarded to exclude one-off allocation inside ONNX Runtime.

| Model | Load (warm) | RTF | 3 min of audio decodes in |
|---|---|---|---|
| tiny | 0.8 s | 0.056 | ~10 s |
| base | 1.0 s | 0.097 | ~17 s |
| **small** | 2.2 s | **0.339** | **~61 s** |

RTF rises with clip length (small: 0.284 at 6.6 s, 0.360 at 16.7 s), so expect
the upper end in practice — VAD segments run up to 20 s.

**`small` is the shipped default.** It meets the target of roughly one minute of
processing per three minutes of speech, at the best Portuguese accuracy of the
three. Because recognition runs live during recording, the only wait you actually
notice is the final segment (~7 s worst case), not the whole recording.

**Cold start is different from the table.** The first load after boot took 22.5 s
against 2.2 s warm — that is page-cache-cold I/O over FUSE reading 375 MB, not
model initialisation. Subsequent launches are fast.

Reproduce:

```bash
adb push .bench/test_wavs/0.wav /sdcard/Android/data/com.presstotalk.mobile.dev/files/bench/
adb shell am start -n com.presstotalk.mobile.dev/com.presstotalk.mobile.bench.BenchmarkActivity
adb logcat -s Benchmark:I
```

Speed is all this settles. Portuguese transcription *quality* is a judgement
call — switch models in Settings and compare.
