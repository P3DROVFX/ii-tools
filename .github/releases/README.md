# Release notes

One file per version, `<version>.md`, named as `versionName` in `app/build.gradle.kts`.
Pushing both to `master` releases it (`.github/workflows/release.yml`): signed APK, banner,
tag and GitHub release, nothing by hand.

Above the first `---`: the banner's words (`tools/make-release-banner.py`), a subtitle and
up to four short highlights. Below it: the release text, in Markdown. The workflow adds the
banner on top and the install line and signing certificate at the end.

```
subtitle: Smooth Stream
- Second Screen over UDP
- HEVC up to 120 Hz
---
**What this release is about**, in one sentence.

### A section
- **A change:** what it does for the user.
```

Preview the banner locally with `python3 tools/make-release-banner.py` (it reads the version
from the Gradle file, or `--version`).
