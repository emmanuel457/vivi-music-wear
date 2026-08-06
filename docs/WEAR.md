# Vivi Music for Wear OS

A standalone-capable Wear OS app that pairs with the Vivi Music phone app,
modelled on how YouTube Music and Spotify behave on a watch: control the phone
when it's playing, stream on your own when it isn't.

Built on top of [vivi-music](https://github.com/vivizzz007/vivi-music). GPL-3.0,
same as upstream.

---

## What was added

Three things, none of which change how the phone app behaves on its own:

| Module | What it is |
| --- | --- |
| `:wear` | The watch app. New. Wear Compose Material 3, its own media3 player, ~20 Kotlin files. |
| `:wearsync` | The wire protocol shared by both sides. New. DTOs + Data Layer path constants. |
| `:app` | The existing phone app, plus a `com.music.vivi.wear` package and three lines in `MusicService`. |

The phone app was **not** stripped down. The watch app is the stripped-down
thing — it reuses `:innertube` verbatim and reimplements only playback and UI.
Removing features from `:app` would have removed them from your phone.

---

## How playback routing works

The watch decides per-action whether a control acts locally or on the phone.

```
User taps play on the watch
         │
         ├── Preference = "Phone"  ──────────────► send CMD_PLAY_TRACKS
         ├── Preference = "This watch" ──────────► play locally
         └── Preference = "Auto" (default)
                    │
                    ├── phone reachable AND already playing ──► send to phone
                    └── otherwise ───────────────────────────► play locally
```

The rule is *last actor wins*. Starting playback on the watch sends
`NOTIFY_WATCH_PLAYING`, and the phone pauses — you never get the same song out of
two devices. If a send fails because the phone dropped out between the routing
decision and the message, the watch falls back to local playback rather than
dropping the tap.

Position is never polled. The phone stamps each state snapshot and the watch
extrapolates locally; polling would hold the Bluetooth link open and cost real
battery on both devices.

---

## Data Layer protocol

Defined in `wearsync/.../SyncProtocol.kt`.

| Transport | Paths | Why |
| --- | --- | --- |
| `DataClient` | `/vivi/state/np`, `/vivi/state/auth` | Retained and replayed on reconnect, so a watch that was out of range catches up on its own. |
| `MessageClient` | `/vivi/cmd/…`, `/vivi/req/…` | Fire-and-forget, low latency, correctly dropped when the peer is gone. |
| `ChannelClient` | `/vivi/channel/library` | A real library exceeds the Data Layer's 100 KB per-item ceiling. |

Capabilities: the phone advertises `vivi_music_phone`, the watch advertises
`vivi_music_wear`, each discovering the other by name.

### Account handoff

The watch cannot complete a Google sign-in on its own, so it asks the phone.
The phone **always** shows a confirmation prompt before releasing anything —
the request arrives over the Data Layer with no human in the loop, and the
payload is an account cookie. Approving is a deliberate tap on the phone.

---

## Building

Requires JDK 21 and the Android SDK (platform 37, build-tools 37.0.0, NDK
27.0.12077973 for the phone app's native code).

**Generate the shared keystore first.** Phone and watch APKs must carry the same
signing certificate or the Data Layer silently delivers nothing between them —
no error, no log, just a watch that never sees the phone.

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/vivi-wear.keystore \
  -storepass viviwear -keypass viviwear \
  -alias vivi -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Vivi Music Wear, OU=ViviWear, O=ViviWear, C=US"
```

Override the defaults with `VIVI_STORE_PASSWORD`, `VIVI_KEY_ALIAS` and
`VIVI_KEY_PASSWORD` if you want your own.

Then:

```bash
./gradlew :wear:assembleRelease :app:assembleUniversalFossRelease
```

Outputs:
- `wear/build/outputs/apk/release/wear-release.apk`
- `app/build/outputs/apk/universalFoss/release/app-universalFoss-release.apk`

---

## Installing

Both APKs must be installed — the stock phone build has no Data Layer listener
and the watch will find nothing to talk to.

```bash
# Phone (replaces your existing install; back up first if you care about it)
adb install -r app-universalFoss-release.apk

# Watch, over Wi-Fi debugging
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r wear-release.apk
```

The phone app's application ID no longer carries a `.debug` suffix on debug
builds, because the watch app must share the exact application ID for node
pairing to resolve.

---

## Deliberate omissions

Things the phone app does that the watch does not, and why:

- **PoToken generation, NewPipe fallbacks, Rhino signature deobfuscation.**
  `WearStreamResolver` leans on the ANDROID_VR clients, which return direct
  unciphered URLs. The full fallback chain costs CPU, memory and battery to
  serve a small minority of tracks; a track that fails on the watch plays on the
  phone.
- **JioSaavn, Spotify, Last.fm, Shazam, Discord RPC, Cast, lyrics, canvas.**
  None of these have a coherent watch form factor.
- **Room.** The watch renders lists and never queries or joins. The library
  cache is a single JSON file written atomically.
- **Hilt.** Five singletons and no injection points beyond them. KSP codegen and
  component init are real costs on this hardware. The phone app keeps Hilt.

## Not yet implemented

- **On-watch downloads for offline playback.** The `Downloads` library section
  shows what the *phone* has downloaded and streams it; it does not yet transfer
  audio to the watch. This is the main gap versus YouTube Music on Wear OS.
- **Tiles and complications.**
- **Ongoing Activity** (the playback indicator on the watch face).
