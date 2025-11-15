# DayMind Android Client (EPIC-13 / US-13.1)

Foreground-only Kotlin/Compose app that records mono 16 kHz WAV chunks, queues them locally, and lets WorkManager upload each file to `/v1/transcribe` with the `X-API-Key` header. All AI and finance logic remains on the server; the client only captures and transports audio.

## Local setup
1. Copy `local.properties.sample` to `local.properties` and add your Android SDK path plus DayMind endpoint secrets:

   ```properties
   sdk.dir=/Users/you/Library/Android/sdk
   BASE_URL=https://api.your-daymind-host
   API_KEY=dev-demo-key
   ```

2. Optional (production override): populate `EncryptedSharedPreferences` via a secured settings screen or adb shell so `SERVER_URL` / `API_KEY` keys take precedence over `BuildConfig` fallbacks.

3. Build the APK:

   ```bash
   cd mobile/android/daymind
   ./gradlew assembleDebug
   ```

4. Install on a device or emulator:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Runtime behavior
- The `Record` toggle starts a foreground `AudioRecord` service (PCM 16‑bit, 16 kHz mono) that writes 6 s WAV chunks into `cacheDir/vault/`, trims trailing/leading silence, and captures speech windows for each clip. The **Pending chunks** counter on the home screen increments every ~6 s so you can confirm audio is flowing even before syncing.
- Chunks remain on-device until you tap **Sync Now**. Manual sync concatenates every pending chunk (in chronological order), encodes the session into FLAC, uploads the archive + manifest, and marks the chunks as uploaded—no background uploads every 6 s.
- **Play Last Chunk** lets you audition the latest trimmed clip (button becomes **Stop Playback** while audio is playing).
- **Share Last Chunk** copies the original (untrimmed) WAV into the public Music library (`Music/DayMind/…` via MediaStore on Android 10+ or `Music/DayMind/` on earlier releases) and opens the Android share sheet so you can inspect it in any audio player or file manager.
- **Chunk Vault list** (bottom of the home screen) shows the newest 10 chunks with per-item **Share** actions so you can replay/export older clips without digging through the filesystem manually. Every share action uses the same public path, so external apps (PowerAmp, VLC, etc.) can browse them directly.
- **Share Archive** exposes the last FLAC bundle via the Android Share sheet (files are stored under `Android/data/com.symbioza.daymind/files/Music/` for playback in any external app).

### Manual sync & compression
- Sync builds `archive_<uuid>.flac` + `archive_<uuid>.json`, compressing the concatenated WAV with the Java FLAC encoder (level 8). The JSON manifest lists `{chunk_id, session_start, session_end, speech_segments}` for every trimmed clip.
- Uploads now target `/v1/transcribe/batch` with two form fields: `archive` (FLAC) and `manifest` (JSON). Backend processing splits the archive using the manifest and keeps UTC speech windows intact.
- Nothing leaves the device unless you explicitly tap **Sync Now** (or implement a scheduled sync later). You can keep the FLAC locally even after upload.

### Speech timeline metadata
- Silence trimming uses a lightweight amplitude detector; sections shorter than ~250 ms or below the threshold are discarded automatically.
- Every retained chunk is tracked in `chunks_manifest.json` with relative `{start_ms,end_ms}` pairs describing speech.
- The FLAC manifest converts those windows into absolute UTC timestamps (`start_utc`/`end_utc`) so `/v1/transcribe/batch` and downstream GPT summarization know exactly when you were talking, even though the uploaded archive is trimmed.

### Manual sync flow
1. Record as long as you like; pending chunk count grows (visible on the main screen) but nothing leaves the device.
2. Tap **Sync Now** when you want to upload. The button shows “Syncing…” while the FLAC archive + manifest upload; afterwards the pending counter resets to 0.
3. Tap **Share Archive** to send the latest FLAC elsewhere (email, Drive, standalone audio app, etc.). Files live under `Android/data/<app id>/files/Music/`.
4. Tap **Share Last Chunk** whenever you want the raw WAV for a single clip (with silence intact). Every chunk is mirrored to the device’s Music library under `Music/DayMind/` (scoped storage on Android 10+); on Android 9 and below you’ll be prompted for the legacy storage permission the first time we write to the shared Music folder.
4. Archives and manifests remain on-device; delete them manually if storage is tight.

## Privacy notes
- Audio chunks stay on-device (cache directory) until they upload over HTTPS.
- No AI inference, ledgers, or summaries run locally; the FastAPI backend continues to own all Text-First artifacts.
- Delete the app data to purge cached chunks if needed.

## Build requirements
- JDK 17 (Temurin/Azul/OpenJDK)
- Android SDK Platform 34 + Build Tools 34.0.0 (install via `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"`)
- Gradle wrapper bundled in this repo (`./gradlew`)

Local builds stay deterministic with:
```bash
./gradlew assembleDebug               # debug (default local target)
./gradlew assembleRelease             # release (unsigned unless signing props configured)
```
Copy `gradle.properties.template` to `gradle.properties` (or export `ORG_GRADLE_PROJECT_*` vars) when providing signing credentials locally.

## CI
`.github/workflows/android_build.yml` assembles debug/release APKs on pushes to `main`, pull requests, tags, and manual dispatches. Manual triggers stay CLI-first:
```bash
gh workflow run android_build.yml -f build_type=debug -f runner=gh --ref main
gh workflow run android_build.yml -f build_type=release -f runner=gh --ref main
gh workflow run android_build.yml -f build_type=both -f runner=self -f ref=feature/android-ci
```
Artifacts land as `daymind-android-*` on each run; tag builds also attach the APKs to the GitHub Release. Set the optional signing secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_ALIAS_PASSWORD`) or matching `gradle.properties` entries to emit `app-release-signed.apk` in addition to the default debug + unsigned release packages.

### UI — True Black + Logo
- Backgrounds and surfaces default to `#000000` (true black) for legacy and Android 12+ splash flows.
- Primary blue (`#375DFB`) remains the accent color for buttons and interactive elements.
- Splash/icon art lives in `app/src/main/res/drawable/daymind_logo.xml` (vector) with the source SVG mirrored under `mobile/android/daymind/art/`.
