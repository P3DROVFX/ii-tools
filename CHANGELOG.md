# Changelog

## Unreleased

- **Updates in the app:** a card on the home, a row in Settings and one quiet notification
  when a new version is out, with the changelog of every release since yours and a direct
  download from GitHub.
- **Volume keys for the PC:** an option (Audio and Settings) for the phone's volume buttons
  to change the PC's volume while the phone itself plays nothing.
- **Bluetooth batteries on the PC:** the battery of the earbuds, watch or headset connected
  to the phone shows next to the phone's own in the shell.
- **Fixed:** the PC's sound during a Wi-Fi second-screen session (needs the shell's
  `ii_link` helper rebuilt: *Settings → ii tools* flags an outdated helper).

## 1.0.0

First public release.

- **Link:** pairing by QR code or `iilink://` link, a pinned TLS WebSocket to the shell's
  `ii_link` helper on the LAN or Tailscale, kept alive in the background with a ping a minute.
- **Home:** a dashboard of the PC (lock, what plays, volume with output and input pickers,
  live activity, tools, controls, load, the phone as the PC's microphone, the PC's clipboard).
- **Media Mode:** a landscape full-screen player with cover-tinted backdrop and synced lyrics.
- **Tools:** touchpad, deck, draw, present, second screen (extend or mirror over USB),
  webcam, microphone, file sharing both ways, SFTP browsing of the phone, screenshots and
  gallery, calls, clipboard, Wake-on-LAN, routines and Modes triggers, phone status and
  data (alarm, contacts, screen time).
- **Live notifications relay** for the shell, including Android 16 Live Updates.
- Material 3 Expressive with Material You colour, in English and Portuguese (Brazil).
