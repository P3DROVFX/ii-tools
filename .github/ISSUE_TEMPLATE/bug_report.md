---
name: Bug or crash report
about: Report a crash, unexpected behavior, or integration problem
title: ""
labels: bug
assignees: ""
---

### What happened
<!-- Describe the bug or crash clearly. If it is a crash, mention whether the app was open or in the background. -->

### Steps to reproduce
1.
2.
3.

### Environment
- **ii tools version:** (e.g. `1.5.2` — shown in *Settings → Updates*)
- **Phone & Android version:** (e.g. `Galaxy S23, Android 16 / One UI 8`)
- **Shell branch / version:** (e.g. `ii-p3drovfx` `dev`, latest commit)
- **Connection route:** LAN / USB / Tailscale

### Diagnostic log (please attach)
> **How to get the log (no ADB required, ii tools 1.5.2+):**
> 1. Open **ii tools** on your phone.
> 2. Go to **Settings → Diagnostics & logs**.
> 3. Tap **Share** (or tap **Send diagnostic log** to preview and **Copy**).
> 4. Drag and drop the exported `ii-tools-log-*.txt` file into this issue (or paste it below).
>
> The diagnostic report includes device/ROM info, link stats, recorded crash stack traces, Android process exit reasons (native crashes / ANRs), and the app's own logcat. It **never** includes passwords, tokens, clipboard contents, or notification text.
>
> **If the app cannot open at all** (or on older versions), you can grab the crash over USB:
> ```sh
> adb logcat -d *:E | grep -i -C 10 "dev.ii.tools"
> ```
> If the issue involves the PC side, also attach relevant lines from the shell log (`qs log -c ii -t 200 --no-color`).

<!-- Attach ii-tools-log-*.txt here or paste it inside the details block below -->
<details>
<summary>Diagnostic log</summary>

```
Paste log here or attach the .txt file above
```

</details>
