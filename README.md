# ii tools

Android companion for the [ii](https://github.com/P3DROVFX/ii-vynx) Quickshell desktop: a
helper alongside KDE Connect for the things it does not do and that only make sense
with the ii shell. It talks to the shell over its own encrypted link (**ii link**), and
the live notification relay still rides on the KDE Connect pairing. The plan is in
[ROADMAP.md](ROADMAP.md).

Kotlin + Jetpack Compose, Material 3 Expressive with Material You dynamic colour.
Each integration is a destination on the home hub (`Destination` in `MainActivity`).

## Integrations

### ii link (connection to the PC)

A TLS WebSocket straight to the shell's `ii_link` helper, on the LAN (or Tailscale).
The full contract is the shell's `scripts/iiLink/PROTOCOL.md`.

- **Pairing:** on the PC, *Settings → ii tools → Pair a phone* shows a QR code. The app
  scans it with Google's code scanner (no camera permission), or opens the same
  `iilink://pair?…` link sent from the PC over KDE Connect. The PC's self-signed
  certificate is pinned by its SHA-256; the PC keeps only a hash of this phone's token.
- **Lifetime:** the socket is open only while the app is in front, plus 30 s. No
  service, wakelock or socket otherwise. If the PC's address changed, the app finds it
  again with a UDP broadcast on the link's port.
- **Remote control:** lock, lock + always-on display, always-on display, media mode,
  scratchpad. Each is a toggle that shows the shell's real state (`state`), whatever
  changed it: a lock from a keybind lights the tile up too. Unlocking is refused on
  purpose; it stays with the PC's own authentication. Below the tiles: the workspaces
  (1–10 and any higher one in use, tonal when they hold windows), the shell's modes and
  its presets. The shell announces which actions exist (`hello`), so switching an
  integration off on the PC hides it here.
- **PC audio:** the default output's volume and the mic, which device plays and records,
  volume and mute per app, and EasyEffects (on/bypass and output preset). Live from the
  shell's PipeWire state; a slider follows the finger while held and sends at most ~20
  values a second.
- **Deck:** the phone as a stream deck. Keys come from the shell's catalog (screenshots,
  recording, media, windows, look, Discord, your own commands from *Settings → ii tools*),
  modes, presets, workspaces and apps. Toggle keys light up with the shell's real state.
  Pages fit the screen; *Edit* adds, removes and reorders (long press, drag); *Dock mode*
  turns the phone sideways, keeps the screen on and dims it. The layout lives on the phone.
- **Icons:** any Material Symbol the shell names is drawn by the PC's helper from its own
  font, once, and cached on the phone; the app bundles only what its screens use.
- Android 17 needs the *Nearby devices* permission (`ACCESS_LOCAL_NETWORK`) to reach
  the LAN.

### Live updates relay

Android live notifications (an iFood order on its way, a ride arriving, Android 16+
Live Updates) are *ongoing* notifications, and KDE Connect never forwards ongoing
notifications. Many apps also draw them with a custom layout, leaving the title and
text empty, so not even `adb shell dumpsys notification` can read what they say.

ii tools listens to notifications (`NotificationListenerService`) and, for each live
one, inflates its layout off screen, reads the visible texts and progress bars, and
re-posts the result as a plain, silent notification. KDE Connect forwards that copy
over the existing pairing; the ii shell recognises it and shows it as a live update
(a shell notification kept in step with the phone, an entry in the Phone tab and a
glance on the dynamic island). Nothing to configure on the desktop.

#### What counts as live

*Auto* (default) relays ongoing notifications that are promoted Live Updates, use
`ProgressStyle`, or whose channel/group id contains the word `live`. Media players and
group summaries are skipped. In the app, each app with an ongoing notification can be
switched to *Always* or *Never*.

#### Wire format

    title: "<App> · <headline>"
    text:  "iilive1:" + base64url(JSON)

```json
{"v":1,"id":"b396d1f7","pkg":"br.com.brainweb.ifood","app":"iFood",
 "posted":1794178240459,"color":"#c01d31","title":"Previsão de entrega",
 "text":"20:46 - 20:56","lines":["Seu pedido saiu para entrega","…"],
 "progress":500,"max":1000}
```

Optional keys: `sub`, `crit` (Live Updates short critical text), `indeterminate`,
`chrono` + `countdown`. The copy is cancelled when the source notification goes away.

## Build

Needs the Android SDK (`sdk.dir` in `local.properties` or `$ANDROID_HOME`) and a JDK 17+.

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell cmd notification allow_listener dev.ii.tools/dev.ii.tools.live.LiveListener
    adb shell pm grant dev.ii.tools android.permission.ACCESS_LOCAL_NETWORK

`tools/measure.sh` checks the performance budgets (helper RSS, CPU, wakeups; app PSS
and wakelocks).

Sideloaded apps on Android 13+ need *Allow restricted settings* (App info → ⋮) before
notification access can be switched on from the phone; the `allow_listener` line does
it over adb.
