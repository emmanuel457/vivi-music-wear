# Vivi Connect

Spotify-Connect-style playback control between full Android devices — phone,
tablet, another phone. Play on one, see and drive it from any other.

This is a **second transport**, sitting alongside the Wear Data Layer rather
than replacing it. The Data Layer only ever connects a handheld to a paired
Wear OS watch; it will never see a tablet, no matter how it is configured. Both
transports carry the same `:wearsync` payloads, which is why that module
contains no transport types.

---

## How it compares to the real thing

| | Vivi Connect | Spotify Connect |
| --- | --- | --- |
| Topology | Peer-to-peer over the LAN | Every client registers with Spotify's backend |
| Server | None | Required |
| Range | Same Wi-Fi network | Anywhere — LTE phone controls a desktop at home |
| Auth | Shared account fingerprint | Account session server-side |
| Discovery | mDNS (`NsdManager`) | Server-side device list |

The trade is deliberate: no backend to run, no playback data leaving the house,
and nothing to keep paying for — at the cost of only working while devices share
a network.

---

## Design

Every device runs identical code and is simultaneously a **controller and a
target**, which is the shape that makes Connect feel the way it does. Whichever
device holds audio broadcasts its `NowPlayingState` to every linked peer.

```
        NSD advertise / discover  (_vivimusic._tcp)
   Phone  ─────────────────────────────────────────►  Tablet
     ▲                                                   │
     │        TCP, newline-delimited JSON frames          │
     └───────────────────────────────────────────────────┘
        state broadcast ▲          ▼ transport commands
```

**Discovery** — `NsdManager`, not Nearby Connections: no Play Services
dependency, no location permission, and on a stable Wi-Fi network it is both
faster and far less battery-hungry than Nearby's Bluetooth scanning.

**Transport** — one TCP socket per peer, newline-delimited JSON. The payloads
are already JSON, so a length-prefixed binary framing would buy nothing at these
sizes while being harder to debug from a socket dump. `TCP_NODELAY` is set:
transport commands are tiny and latency-sensitive, and Nagle would sit on them
waiting for bytes that never arrive.

**Dial arbitration** — both devices discover each other at the same moment, so
only the peer with the lower device id dials. Otherwise every pair ends up with
two redundant sockets.

---

## Security

An open TCP listener on the LAN is a real exposure: anything on the same Wi-Fi
could otherwise drive your playback, and a café or office network is not a trust
boundary.

Devices authenticate with a fingerprint derived from the signed-in account:

```
fingerprint = SHA-256("vivi-connect-v1:" + account email)
```

Two devices signed into the same account derive the same value and accept each
other. A peer advertising a different fingerprint is never even shown in the
picker — surfacing it and failing at handshake time would just look broken.

**Connect is unavailable when signed out.** There would be no shared value to
authenticate with, and "accept every device on the LAN" is not an acceptable
default. This is a deliberate limitation, not an oversight.

---

## Using it

1. Install the same APK on both devices.
2. Sign into the same account on both.
3. Put both on the same Wi-Fi.
4. **Settings → Devices** on either one.

The picker lists every device it can see. Tapping one hands the current track to
it; the transport row drives whichever device currently holds the audio.

---

## Known limitations

- **Same network only.** By design — see the comparison table above.
- **Transfer moves the current track, not the whole queue.** The full queue is
  capped at `PlayTracksCommand.MAX_TRACKS` on the wire; transfer currently sends
  only the playing track.
- **Some networks block mDNS.** Guest and enterprise Wi-Fi frequently disable
  multicast between clients, which makes discovery silently find nothing.
- **Not tested across two physical devices.** The code compiles and the protocol
  is symmetric, but no second Android device was available to verify discovery,
  handshake and transfer end to end.
