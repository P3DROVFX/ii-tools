# ii tools

Android companion for the [ii](https://github.com/P3DROVFX/ii-vynx) Quickshell desktop.

## Live updates relay

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

### What counts as live

*Auto* (default) relays ongoing notifications that are promoted Live Updates, use
`ProgressStyle`, or whose channel/group id contains the word `live`. Media players and
group summaries are skipped. In the app, each app with an ongoing notification can be
switched to *Always* or *Never*.

### Wire format

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

Needs only the Android SDK (`$ANDROID_HOME`, default `~/Android/Sdk`) and a JDK 17+;
no Gradle.

    ./build.sh            # build/ii-tools.apk, signed with the debug key
    ./build.sh --install  # + adb install, and grant notification access

Sideloaded apps on Android 13+ need *Allow restricted settings* (App info → ⋮) before
notification access can be switched on from the phone; `--install` grants it over adb.
