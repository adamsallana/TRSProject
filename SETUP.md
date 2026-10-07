# TRSProject — Team Setup Guide (Story 1: Live Transcription)

This guide gets all 5 team members from a fresh clone to a working local
environment for **Story 1 only**: capture microphone audio on Android (a real
or mocked call), stream it to our Ktor backend over a WebSocket, forward it to
Google Speech-to-Text, and show the live transcript back on the client.

WebRTC app-to-app calling and the Android Telecom API (native call ringing)
are planned for **later stories** and are intentionally not covered here —
this doc will be extended when that work starts.

## 1. Architecture (Story 1 slice)

```
Android client --(WebSocket, raw/PCM audio chunks)--> Ktor server
Ktor server    --(streaming audio)------------------> Google Speech-to-Text
Google STT     --(partial/final transcripts)--------> Ktor server
Ktor server    --(WebSocket, transcript messages)---> Android client
```

Supabase sits alongside the Ktor server for auth and/or persisting transcript
records. Render hosts the deployed Ktor server (push-to-deploy from `main`).

## 2. Repo layout

```
/android        Android Studio project (Kotlin)
/server         Ktor backend (Gradle Kotlin DSL)
/docs           Additional design docs (as they come up)
SETUP.md        This file
README.md       Project overview, points here
.gitignore      Shared ignore rules (Android + Gradle/Ktor + secrets)
docker-compose.yml   Local-dev container for the server
```

This is a monorepo — one `git clone` gets both client and server. Each lives
in its own Gradle project (`/android` and `/server` each have their own
`settings.gradle.kts`), so open `/android` directly in Android Studio rather
than the repo root.

## 3. Prerequisites (install once, per member)

| Tool | Notes |
|---|---|
| **Android Studio** (current stable) | Install an SDK platform + at least one emulator image (API 26+), or use a physical device with USB debugging enabled. |
| **JDK 21** | LTS release, fully supported by current Gradle/Ktor and the JBR Android Studio bundles. For the standalone `/server` project make sure `JAVA_HOME` points at JDK 21. |
| **Git** | Standard clone/branch/PR workflow. |
| **Docker Desktop** | Lets anyone run the Ktor server locally without a full Kotlin toolchain, and keeps local/prod parity. |
| **Password manager / shared vault access** | Team's shared vault (e.g. a shared Bitwarden folder) holding the `.env` secrets — ask the backend lead for an invite before starting section 8–9. |

Gradle itself does **not** need a separate install — both `/android` and
`/server` use the Gradle wrapper (`./gradlew`), which downloads the pinned
version automatically.

## 4. Clone and branch

```bash
git clone <repo-url> TRSProject
cd TRSProject
git checkout -b <yourname>/<short-task-description>
```

Open a PR into `main` when ready for review; don't push directly to `main`.

## 5. Android client setup

1. Open Android Studio → **Open** → select the `/android` folder (not the
   repo root).
2. Let Gradle sync finish on first open.
3. `minSdk 26` / target the latest stable SDK — Story 1 needs modern
   `AudioRecord` behavior for mic capture.
4. Manifest permissions needed for Story 1:
   - `android.permission.RECORD_AUDIO`
   - `android.permission.INTERNET`
5. Dependencies to add for this story:
   - A WebSocket client — either OkHttp's built-in WebSocket support or the
     Ktor client (`ktor-client-okhttp` + `ktor-client-websockets`) if we want
     one networking stack shared conceptually with the server.
   - Android's `AudioRecord` (platform API, no extra dependency) to capture
     raw mic PCM frames for streaming.
6. Pointing the client at the backend:
   - Emulator → local server: `ws://10.0.2.2:<port>/...` (10.0.2.2 is the
     emulator's alias for the host machine's localhost).
   - Physical device → local server: use your machine's LAN IP instead of
     `10.0.2.2`, same Wi-Fi network.
   - Against the deployed server: the Render URL (`wss://...`).
   - Keep this as a `BuildConfig` field (or build-type-specific value) so
     debug builds default to local and release builds default to Render.

## 6. Backend setup (Ktor)

1. Open `/server` as its own project (IntelliJ IDEA or Android Studio both
   work — it's a plain Gradle Kotlin project, no Android plugin involved).
2. Core dependencies to add in `server/build.gradle.kts`:
   - `io.ktor:ktor-server-core`
   - `io.ktor:ktor-server-cio` (or `ktor-server-netty`)
   - `io.ktor:ktor-server-websockets`
   - `com.google.cloud:google-cloud-speech` (Google Speech-to-Text client)
   - A Supabase client, or plain Postgres/REST calls against Supabase
     depending on what Story 1 actually persists.
3. Run locally: `./gradlew run` from `/server`.
4. The server exposes a WebSocket route that Android connects to, streams
   audio chunks into, and receives transcript messages back from — this
   route is the first thing to implement once setup is done.
5. Environment variables the server reads (see `.env.example` once created):
   - `GOOGLE_APPLICATION_CREDENTIALS` — path to the GCP service-account JSON.
   - `SUPABASE_URL`, `SUPABASE_KEY`
   - `PORT`
   Load them locally via a small dotenv loader, or via Docker Compose (below)
   — never hardcode them in source.

## 7. Docker (local dev)

As part of backend setup, `/server` gets a multistage `Dockerfile` (a Gradle
build stage, then a slim JRE runtime stage that just runs the built jar) and
the repo root gets a `docker-compose.yml` that runs that image with the
shared `.env` injected and the WebSocket port exposed — so anyone on the team
can run the backend without installing a Kotlin toolchain:

```bash
docker compose up --build
```

This also keeps local behavior close to what Render runs in production,
since Render will build from the same Dockerfile.

## 8. Google Speech-to-Text setup

1. One shared GCP project (ask the backend lead if you need access) with the
   **Speech-to-Text API** enabled.
2. A service-account key (JSON) is created once and shared via the team's
   password manager — do not generate your own personal key for this
   project.
3. Save the JSON locally at `server/secrets/gcp-key.json` (this path is
   gitignored) and set `GOOGLE_APPLICATION_CREDENTIALS` to point at it in
   your local `.env`.
4. For Story 1, the server will use the **streaming recognize** API,
   feeding it audio chunks as they arrive over the WebSocket from Android
   and emitting partial/final transcripts back.

## 9. Supabase setup

1. One shared Supabase project (ask the backend lead for an invite/API
   keys).
2. Add `SUPABASE_URL` and the relevant key (anon or service key, depending
   on whether calls go through client-side or server-side) to your local
   `.env`, sourced from the shared vault.
3. For Story 1, Supabase's role is likely limited to auth and/or storing
   transcript records — expand this section as that's implemented.

## 10. Render deployment

1. Connect the GitHub repo to Render as a **Docker-based web service**
   pointed at `/server` (so it builds the same Dockerfile used locally).
2. Configure the same environment variables as the local `.env` in Render's
   dashboard, values from the shared vault (the GCP key can be set as a
   Render **secret file**, mounted at the path `GOOGLE_APPLICATION_CREDENTIALS`
   expects).
3. Enable push-to-deploy on merges to `main`.

## 11. End-to-end smoke test

After setup, each member should be able to:

1. Start the server — either `./gradlew run` in `/server`, or
   `docker compose up --build` from the repo root.
2. Run the Android client (emulator or device) pointed at that local server.
3. Grant the mic permission, speak (or play a mocked call recording).
4. See a live transcript appear on screen within a couple seconds of
   speaking.

If all 5 members can do this, Story 1 infrastructure is ready for feature
work.

## 12. Later stories (not covered yet)

- **WebRTC** — real app-to-app calling, with the mic stream for Story 1
  eventually sourced from the WebRTC audio track instead of a raw
  `AudioRecord` capture.
- **Android Telecom API** — integrating with the system dialer so incoming
  calls ring natively.

This doc will grow a section for each once that story starts.
