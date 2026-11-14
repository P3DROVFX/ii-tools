# ii tools

Android companion for the [ii](https://github.com/P3DROVFX/ii-vynx) Quickshell desktop,
in place of KDE Connect: it talks to the shell over its own encrypted link (**ii link**)
and needs nothing else on the phone or the PC. The plan is in [ROADMAP.md](ROADMAP.md).

Kotlin + Jetpack Compose, Material 3 Expressive with Material You dynamic colour, in
Google Sans Flex (the ii shell's face). The home is a dashboard of the PC: its card (the
lock, its state), what plays on it, its volume with output and input as split buttons
(the device list opens in a bottom sheet), what it is doing live, the tools used most,
its quick toggles and its load. Every tool is in *Tools*, every permission and switch in
*Settings* (where pairing lives too); each screen is its own activity (`Dest` →
`MainActivity`).

## Integrations

### ii link (connection to the PC)

A TLS WebSocket straight to the shell's `ii_link` helper, on the LAN (or Tailscale).
The full contract is the shell's `scripts/iiLink/PROTOCOL.md`.

- **Pairing:** on the PC, *Settings → ii tools → Pair a phone* shows a QR code. The app
  scans it with Google's code scanner (no camera permission), or opens the same
  `iilink://pair?…` link. The PC's self-signed
  certificate is pinned by its SHA-256; the PC keeps only a hash of this phone's token.
- **Lifetime:** the link stays open in the background (*Stay connected*, on by default),
  so the PC can reach the phone at any time; it needs the app let off battery
  optimisation (a guided step), or Doze cuts it. No foreground service: the process lives
  through the notification listener. A ping a minute; away from the PC it retries every
  few minutes, and at once when the network changes. Turned off, the link is open only
  while the app is in front (plus 30 s) or for a burst of notifications. If the PC's
  address changed, the app finds it again with a UDP broadcast on the link's port.
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
  modes, presets, workspaces, apps (with their PC theme icon when cached) and one-level folders.
  Toggle keys light up with the shell's real state, including inside folders. Pages fit the
  screen; *Edit* adds keys and folders, renames or deletes folders, moves keys between the top
  level and folders, and reorders (long press, drag); *Dock mode* turns the phone sideways,
  keeps the screen on and dims it. The layout lives on the phone.
- **Wallpaper:** share an image to *Set on PC* (or pick one in the app) and choose desktop,
  lock screen or both. The original file goes over the link in 256 KiB frames; the shell
  saves it with the other wallpapers and applies it, and the colours follow.
- **Calls:** with *Phone* permissions and notification access, the app watches the call
  state from its notification listener (no background service) and opens the link itself
  when a call rings. The shell's call card then follows the phone, and its Answer,
  Decline and Silence buttons work without ADB; a contact's call button on the PC places
  the call from the phone while the link is up.
- **Notifications:** the phone's notifications reach the PC's Phone tab and desktop
  popups over the link, with the app's icon, a picture, actions and quick reply;
  dismissing on either side dismisses on both. The link opens for a burst and closes 2
  min after the last one. Live updates ride the link too while it is up. KDE Connect's
  copies are left out on the PC.
- **Quick Settings and widget:** tiles for Lock PC, Lock + AOD, Media mode and a
  favourite preset (the star in Remote control). With the panel open the tiles show the
  PC's real state; a tap connects, runs and lets the link go. A home screen widget holds
  the first eight top-level deck keys (tapping a folder opens the deck directly in that folder).
- **Send to PC:** in Android's share sheet. A link opens in the PC's browser or goes to
  its AI chat; text becomes a note, a reminder or a task; a photo or any file goes
  to `~/Downloads` as KDE Connect does (never overwriting), to the shell's file shelf, or
  (images) to the AI chat.
- **Clipboard:** what you copy on the PC (text or image) lands on the phone's clipboard
  while the link is up, and the latest copy when it connects. The phone's clipboard goes
  to the PC on request (Android only lets a focused app read it): the *Clipboard to PC*
  tile, *Copy on the PC* in the share sheet, or the Clipboard screen.
- **Screenshots:** one taken on the phone opens in the shell's screenshot preview and
  lands in `~/Pictures/Screenshots/Phone`. The other way is opt-in (*Receive the PC's
  screenshots*, off by default): then a screenshot taken on the PC lands in the phone's
  gallery (Pictures/ii tools) with a notification, now or when the link next comes up.
- **Copied text, automatically (optional):** an accessibility service notices a copy and
  reads the clipboard for an instant, the only way Android 10+ allows it without the app
  open. It reads event text only, never the screen.
- **Phone data:** the next alarm (for the PC's clock app), battery with temperature and
  health, network, and contacts with photos (for the Phone tab and the call card), from
  the phone itself instead of ADB and KDE Connect. Contacts go only when they change,
  photos only those the PC lacks; the PC keeps them for when the phone is away.
- **Do Not Disturb:** the shell's own (and modes chosen on the PC) silence the phone; the
  phone's turns on the shell's, or a mode. Only changes are sent, so turning it off by
  hand on either side sticks.
- **PC activity:** the screen recording, the pomodoro, the stopwatch, countdowns, a
  ringing alarm and progress as Android 16 Live Updates, with their buttons (stop, pause,
  snooze). Timers count down on the phone by themselves.
- **Wake the PC:** the shell gives the phone its adapters' MAC addresses; *Wake the PC*
  (in Connect to PC, or the Quick Settings tile) sends the Wake-on-LAN magic packet. The
  PC needs Wake-on-LAN on in its firmware and adapter.
- **Performance:** the PC's CPU, GPU, memory and frame rate (MangoHud), a sample a
  second, only while the screen is open; the PC samples only then. The screen stays on,
  four cards side by side with the phone lying down.
- **Phone media on the PC:** each app playing on the phone appears on the PC as an MPRIS
  player (title, artist, cover, position), so the shell's media widgets, island, lock
  screen and media keys control it, as with KDE Connect.
- **Call audio on the PC:** answer a call on the PC and talk through its speakers and
  microphone: the PC becomes the phone's Bluetooth headset (HFP) for that call only.
  Needs the phone paired with the PC over Bluetooth.
- **Drawing pad:** draw on the phone, held sideways, and the ink appears in the shell's
  live draw on the focused screen, with its tools, colours and undo.
- **Second screen:** the phone as a monitor for the PC over USB: extend (a new screen the
  phone's size) or mirror the focused screen, with touch as the PC's touchscreen. Needs
  adb on the PC and USB debugging on the phone.
- **Webcam:** the phone's camera as the PC's webcam (`/dev/videoN` via `ii_link`) over
  ii link, with lens, zoom, exposure, focus, torch and stabilisation controls, 4:3 or 16:9
  (720p or 1080p). Keeps streaming with the screen off, and the PC can request the
  camera with the phone confirming from a notification.
- **Presenter:** a slide clicker for any presentation app on the PC: next and previous
  (the volume keys too), start, black screen and end, a laser dot on the focused screen
  and a talk timer.
- **Touchpad and keyboard:** the phone as the PC's touchpad (a real multitouch touchpad
  for libinput: tap to click, two-finger scroll, three and four-finger gestures, your
  touchpad settings) and keyboard (any character, whatever the PC's layout).
- **Phone files on the PC:** the PC's file manager opens the phone's storage (an SFTP
  server that runs only while the PC uses it, mounted with sshfs), as with KDE Connect.
  Needs *All files access*.
- **Phone gallery:** browse the phone's photos and videos from the PC, read only
  (nothing is ever deleted or changed from the PC), with live updates when new items
  arrive. Images stream over the link (HEIC/DNG and other formats are sent as JPEG);
  videos play through *Phone files* (SFTP). Needs *Photos and videos* permission and
  defaults to off.
- **PC media on the phone:** what plays on the PC shows as a media player on the phone's
  lock screen and Quick Settings (title, artist, cover, position), with play, skip and
  seek. The phone's own players are never echoed back.
- **Icons:** any Material Symbol the shell names is drawn by the PC's helper from its own
  font, once, and cached on the phone; app keys fetch and cache their 96×96 PNG theme icons
  from the shell on demand (falling back to the app's initial letter); the app bundles only
  what its screens use.
- Android 17 needs the *Nearby devices* permission (`ACCESS_LOCAL_NETWORK`) to reach
  the LAN.

### Live updates relay

Android live notifications (an iFood order on its way, a ride arriving, Android 16+
Live Updates) are *ongoing* notifications, and KDE Connect never forwards ongoing
notifications. Many apps also draw them with a custom layout, leaving the title and
text empty, so not even `adb shell dumpsys notification` can read what they say.

ii tools listens to notifications (`NotificationListenerService`) and, for each live
one, inflates its layout off screen, reads the visible texts and progress bars, and
sends the result over ii link; the ii shell shows it as a live update (a shell
notification kept in step with the phone, an entry in the Phone tab and a glance on the
dynamic island). Nothing to configure on the desktop. While the PC is out of reach the
app also re-posts it as a plain, silent notification, which KDE Connect, where it is
still installed, forwards the same way.

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

## License

ii tools is free software under the **GNU General Public License v3.0 or later**
(`GPL-3.0-or-later`); see [LICENSE](LICENSE).

It reuses code from [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android)
(GPL-2.0-only OR GPL-3.0-only, taken under GPL-3.0) and
[Sefirah](https://github.com/shrimqy/Sefirah-Android) (GPL-3.0) where they already solve a
problem well. Files that come from them keep their original copyright and license
notice, with a line saying what changed. Reused so far:

- `media/PhoneMedia.kt`: from Sefirah's `PlaybackFeature` and `MediaSession`.
- `media/PcMedia.kt`: after Sefirah's `RemotePlaybackFeature`.
- `files/FileServer.kt`: from KDE Connect's `SimpleSftpServer` and `SftpPlugin`.
- `files/NativeStorageFileSystemFactory.kt`: KDE Connect's, unchanged.

The app bundles [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex)
(`res/font/google_sans_flex.ttf`), © Google LLC, under the SIL Open Font License 1.1. The
bundled file keeps its weight, width, roundness and slant axes; grade and optical size
are pinned (fontTools `instancer`) to save 3 MB.

