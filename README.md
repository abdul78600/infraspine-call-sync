# InfraSpine Call Sync

Native Android (Kotlin, MVVM) app that lets agents select the folder where their
phone/recording app saves call recordings, scans it for audio files, matches each
recording with the device's call log, and prepares them for upload to the InfraSpine
CRM. The CRM backend integration is not live yet — the upload pipeline is fully built
and can run in a local **dummy/test mode** until the real API is available.

---

## 0. Just want the installable app? (no Android Studio needed)

This project includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`)
that compiles a ready-to-install APK in the cloud — you never need to install
Android Studio or any SDK locally.

1. Push this project to a GitHub repository (free account is enough):
   ```
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
2. On GitHub, open the repo's **Actions** tab — a "Build APK" run starts automatically.
3. When it finishes (a few minutes), open that run and scroll to **Artifacts**.
4. Download **InfraSpine-Call-Sync-debug-apk** (a zip containing `app-debug.apk`).
5. Copy `app-debug.apk` to your Android phone and tap it to install (you'll need to
   allow "Install unknown apps" for whichever app you used to open it — Settings will
   prompt you the first time).

That's it — no SDK, no IDE, no command line on your machine. Every time you (or I)
push a change, a fresh APK is built automatically and shows up in Actions → Artifacts.

> This produces a **debug** build, which is fine for installing on agents' phones for
> testing/internal use. A signed **release** build (for wider distribution / Play Store)
> needs a signing key — ask if you'd like the workflow extended to produce one.

## 1. Requirements

- Android Studio Iguana (2023.2.1) or newer
- JDK 17 (bundled with recent Android Studio)
- Android device or emulator running **Android 8.0 (API 26)** or above
- Gradle / AGP versions are pinned in `build.gradle.kts` / `gradle/wrapper` — no extra setup needed beyond opening the project

## 2. Setup steps

1. Open the project root folder in Android Studio (`File > Open` → select `InfraSpine-Call-Sync`).
2. Let Gradle sync complete. **Note:** the Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`)
   is a binary that isn't included in this source drop — Android Studio detects this on
   first open and offers to regenerate it automatically (or run `gradle wrapper` once
   if you have a local Gradle install). Either path downloads the pinned Gradle 8.7
   distribution and the rest of the dependencies declared in `build.gradle.kts`.
3. Connect a physical device (recommended — call logs and SAF folders behave more
   realistically than on emulators) or start an emulator with API 26+.
4. Run the `app` configuration. The app installs as **InfraSpine Recordings**.

No `local.properties` secrets are required to build — the CRM URL and agent token
are entered at runtime from the in-app **Settings** screen and stored on-device.

## 3. Required permissions

| Permission | Why it's needed | When requested |
|---|---|---|
| `READ_CALL_LOG` | Match recordings to phone number, call time, duration, and call type | When you tap **Scan Now** for the first time |
| Storage Access Framework folder grant (no manifest permission) | Read audio files from the agent-selected folder | When you tap **Select Recording Folder** |
| `POST_NOTIFICATIONS` (Android 13+) | Allow WorkManager / sync to show progress notifications | On first app launch |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Upload recordings to the CRM and detect Wi-Fi/connectivity | Granted automatically (normal permissions) |

The app **never** requests broad storage permissions (`READ_EXTERNAL_STORAGE` /
`MANAGE_EXTERNAL_STORAGE`) — it only reads inside the single folder the agent
explicitly grants via the system folder picker (Storage Access Framework), and the
permission grant is **persisted** so the agent does not need to re-grant it on every
launch or after a reboot.

If the agent denies `READ_CALL_LOG`, scanning still records the audio files it finds,
but every recording is marked **Unmatched** (phone number / call metadata stays empty)
until the permission is granted and the folder is rescanned.

## 4. How to select the recording folder

1. Open the app — the **Dashboard** shows "No folder selected".
2. Tap **Select Recording Folder**.
3. In the system folder picker, navigate to wherever the phone's recorder app saves
   call recordings (commonly `Internal storage / Recordings / Call` or similar,
   depending on the device manufacturer) and tap **Use this folder**.
4. Android asks you to confirm access — tap **Allow**. The app persists this grant
   (`takePersistableUriPermission`) so you won't be asked again, even after a restart.
5. To switch folders later, tap **Select Recording Folder** again from the Dashboard,
   or go to **Settings → Recording Folder → Change Folder**.

## 5. How scanning works

Tapping **Scan Now** (or pulling to refresh on the Dashboard):

1. Confirms a folder is selected and `READ_CALL_LOG` is granted (requesting it if not).
2. Lists every file in the selected folder via `DocumentFile` (read-only — the app
   never writes to, renames, or deletes the original recordings) and keeps the ones
   that look like audio: extensions `mp3, m4a, amr, wav, aac, ogg`, or any file whose
   MIME type starts with `audio/`.
3. For each audio file, reads `name`, content `uri`, `size`, `lastModified`, and `mimeType`.
4. Loads the device call log (if permission is granted) and matches each recording to
   the **closest** call by comparing the recording's last-modified timestamp against
   both the call's start time and its end time (`start + duration`) — this keeps
   matching robust across recorder apps that timestamp files at call start vs. call end.
   A match is accepted only if it falls within a **3-minute** tolerance window;
   otherwise the recording is stored as **Unmatched**.
5. Inserts new recordings into the local Room database. Files already tracked
   (same content URI **and** size) are skipped — re-scanning never creates duplicates.

Matched recordings start in status **Pending**; recordings without a confident call-log
match start as **Unmatched**, and stay there until they can be matched (e.g. after the
permission is granted and a re-scan runs).

## 6. How upload will connect with the CRM later

The upload contract is fully defined and wired end-to-end, just pointed at a local
**dummy uploader** until the real CRM endpoint exists:

```
POST /api/crm/call-recordings/upload
Content-Type: multipart/form-data
Authorization: Bearer <agent token>

  file              — the audio recording (binary)
  phoneNumber       — matched phone number, or omitted if unmatched
  callStartedAt     — epoch millis of call start
  durationSeconds   — call duration in seconds
  callType          — INCOMING | OUTGOING | MISSED | UNKNOWN
  deviceId          — stable per-install device identifier
  originalFileName  — original file name on the device
```

- `data/remote/CrmApiService.kt` — Retrofit interface describing the endpoint above.
- `data/remote/RealCrmUploader.kt` — copies the SAF-referenced file to a temp cache
  file, builds the multipart request, and posts it. **Activates automatically** once
  a CRM URL + agent token are saved in Settings and **dummy/test mode is turned off**.
- `data/remote/DummyCrmUploader.kt` — simulates a successful upload locally (no
  network call) so the rest of the pipeline — status transitions, retry, dedup,
  dashboard counts — can be exercised before the backend exists.
- `data/remote/CrmApiFactory.kt` — builds the Retrofit/OkHttp client, attaching the
  agent token as a `Bearer` Authorization header via an interceptor that **never logs**
  the token.

**To go live with the real CRM:**

1. Open **Settings**, enter the CRM Server URL and the agent's login/token.
2. Turn **off** "Use dummy/test upload mode".
3. Tap **Save Settings**, then **Sync Now**. Uploads now go to the real endpoint.

If the response JSON shape differs from the placeholder `UploadResponse` (currently
`success`, `recordingId`, `message`), update that data class to match — no other
code needs to change.

## 7. Sync behavior

- **Sync Now** uploads every recording currently in **Pending** or **Failed** status,
  one at a time, persisting each result immediately (so interrupted syncs resume
  cleanly on the next run).
- **Duplicate protection**: a unique index on `(fileUri, fileSize)` means a recording
  can only ever be tracked once; and a recording moves to **Synced** the moment its
  upload succeeds, so it is never re-sent.
- **Retry**: tap **Sync Now** again — failed recordings are retried automatically
  alongside any new pending ones.
- **Sync only on Wi-Fi**: when enabled, sync refuses to run on mobile data (manual or
  automatic) and the auto-sync background job is constrained to unmetered networks.
- **Auto sync**: schedules a periodic background sync (every 30 minutes, via
  WorkManager) that follows the same rules as a manual sync.
- If the CRM URL/token are not configured **and** dummy mode is off, recordings remain
  **Pending** — nothing is silently dropped or marked failed.

## 8. Project structure (MVVM)

```
data/
  local/        Room database, DAO, entities, type converters
  prefs/        EncryptedSharedPreferences-backed secure settings store
  remote/       Retrofit API, real + dummy uploaders, auth interceptor
  repository/   RecordingRepository (scan+match+persist), SyncRepository (upload pipeline)
domain/
  model/        Plain Kotlin models & enums (SyncStatus, CallType, CallLogEntry, …)
  util/         DeviceIdProvider, NetworkMonitor
scan/           SAF folder manager, audio file scanner, call-log matcher
sync/           WorkManager auto-sync worker + scheduler
ui/
  dashboard/    Dashboard screen + ViewModel
  recordings/   Recording list, filters, adapter + ViewModel
  settings/     Settings screen + ViewModel
  common/       Formatters, Event wrapper, permission helpers
```

`AppContainer` is a small hand-rolled dependency container (the app is intentionally
too small to justify Hilt/Koin) exposed via `CallSyncApplication.container`.

## 9. Security notes

- The agent token and CRM URL are stored in **EncryptedSharedPreferences**
  (AES-256-GCM/SIV via Jetpack Security) — never in plain `SharedPreferences`.
- The token is attached to upload requests via an OkHttp interceptor and is
  **never written to logs**, exception messages, or UI.
- Original recordings are **read-only** to the app — nothing is ever deleted,
  renamed, or modified on the device.
- Uploads only run when the agent has explicitly configured a CRM URL + token (or
  opted into dummy/test mode), and only over the network conditions they've allowed.
