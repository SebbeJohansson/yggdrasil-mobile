# Releasing

GitHub Actions builds a signed APK and publishes it as a GitHub Release
(`.github/workflows/release.yml`). Start it in either of two ways:

- **From GitHub (works on the phone):** Actions → release → Run workflow,
  pick the branch (`main`), and type the version, e.g. `0.2.0`. The workflow
  makes the tag `v0.2.0` on the commit it built. It stops if that tag
  already exists.
- **From a terminal:**

  ```bash
  git tag v0.2.0
  git push origin v0.2.0
  ```

The slow part runs first, without asking: **build** makes the release APK,
unsigned, with the version in it, and beside it **test** runs the unit tests,
unless the `build` workflow already passed that exact commit (it runs on every
push to `main`, so a release of `main` usually skips them). Only then does
**publish** wait for you: approve it under the run's **Review deployments**
(the `release` environment). It signs the APK it was handed with `apksigner`,
checks the signature and publishes, in under a minute. Only it can read the
key, and nothing is signed before you approve, so a tag pushed with a stolen
token never reaches your phone on its own. Start a release as soon as `main`
has what you want: there is no need to wait for `main`'s build first.

- The tag sets the version: `v1.2.3` is versionName `1.2.3` and versionCode
  `10203`. Each part must stay below 100.
- A tag with a suffix (`v1.0.0-rc.1`) is published as a pre-release.
- The release has the APK, its SHA-256, and notes generated from the
  commits since the last release.
- A failing test, or a build that fails, stops the release before you are asked.
- Releases publish one at a time (`concurrency: release` on publish): one
  waiting for approval holds the next one's publish, not its build. Cancel a
  release you no longer want rather than leaving it waiting.

## The signing key (once)

Android installs an update only when it's signed with the same key as the
installed app. Make one key, keep it safe, and never change it.

```bash
keytool -genkeypair -v -keystore yggdrasil-release.jks -alias yggdrasil \
  -keyalg RSA -keysize 4096 -validity 10000
```

Make a `release` environment (Settings → Environments → New environment) with
yourself as a **required reviewer** and **deployment branches and tags**
limited to `main` and `v*` tags. Add four secrets to that environment, not to
the repository, so only an approved release run can read them:

| Secret | Value |
| --- | --- |
| `YGG_KEYSTORE_BASE64` | `base64 -w0 yggdrasil-release.jks` |
| `YGG_KEYSTORE_PASSWORD` | the keystore password |
| `YGG_KEY_ALIAS` | `yggdrasil` |
| `YGG_KEY_PASSWORD` | the key password (the same as the keystore password unless you set another) |

Keep `yggdrasil-release.jks` and its passwords outside the repo, e.g. in a
password manager. If the key is lost, the next release has to be installed
fresh, which loses the app's data (models, alerts, messages).

## Install a release

Download the APK from the release on your phone, open it, and allow
installing from that app when Android asks. An APK from the `build`
workflow is debug-signed, so it can't update a release install, and the
other way round. Uninstall one before installing the other.

**Blocked by Play Protect?** Google blocks apps that can read notifications
(Huginn) when they're installed from a browser or file manager. Either
turn off *Scan apps with Play Protect* in the Play Store for the install and
back on after, or install over USB with `adb install -r yggdrasil-X.Y.Z.apk`,
which the block doesn't cover.

## Size

Release builds are shrunk by R8 (`isMinifyEnabled`, `isShrinkResources`):
about 28 MB instead of 52. Most of what's left is LiteRT-LM's native
library (23 MB); the models download separately. `app/proguard-rules.pro`
keeps LiteRT-LM whole, because its native code reaches its Java classes by
name. If a release crashes where a debug build doesn't, a keep rule is the
first thing to suspect.

## Build a signed APK locally

```bash
YGG_VERSION_NAME=0.2.0 \
YGG_KEYSTORE_FILE=/path/to/yggdrasil-release.jks \
YGG_KEYSTORE_PASSWORD=… YGG_KEY_ALIAS=yggdrasil YGG_KEY_PASSWORD=… \
./gradlew :app:assembleRelease
```
