# Notes for Claude

## Every change ships a release

The owner installs the apps from releases and runs the server from its image. **Any change you make
must end with a new release**, even when the request doesn't mention one:

1. Bump `versionCode` (+1) and `versionName` in `android/build.gradle.kts`. Android won't install
   an update whose `versionCode` isn't higher than the installed one. That `versionName` is the
   whole project's version: the server and its image use it too (`server/build.gradle.kts` reads
   it), and so does the Linux app: set the same `version` in `desktop/Cargo.toml` (the release
   checks they match).
2. Run the checks; all must pass:
   - `./gradlew :server:test :android:testDebugUnitTest :android:lintDebug :android:assembleRelease`
     (the server's tests run against GreenMail, a real IMAP/SMTP server, in-process; the Android
     tests include an end-to-end run against the real server).
   - When `web/` changed: `cd web && npm ci && npm run build` (it type-checks).
   - When `desktop/` changed: `cd desktop && cargo test && cargo clippy --all-targets && cargo build --release`,
     then `cargo deb --no-build` and `cargo generate-rpm`.
3. Write the release notes to `release-notes/vX.Y.Z.md`, then commit and push. Start the
   **Release** workflow (`.github/workflows/release.yml`, workflow_dispatch) on the branch with
   `version: vX.Y.Z`. Its `release` job runs the tests, builds the signed APK, creates the tag and
   publishes the GitHub release (`SuperAwesomeMagicalEmail-vX.Y.Z.apk`); `desktop` adds
   `SuperAwesomeMagicalEmail-Desktop-vX.Y.Z-amd64.deb` and `-x86_64.rpm`; `image` pushes
   `ghcr.io/603madison/superawesomemagicalemail` (amd64 and arm64). Check that all three succeed.
   (Claude sessions can't push tags; pushing a `v*` tag also triggers the workflow.)
4. Send the owner the APK (and the desktop packages when `desktop/` changed) in chat, renamed like
   the release's.

## Signing

Every release must be signed with the same key, or installed copies can't be updated without
uninstalling. Never generate a replacement key. The key is not in git: CI reads it from the
`RELEASE_KEYSTORE_BASE64` (`base64 -w0 release.jks`) and `RELEASE_KEYSTORE_PASSWORD` repository
secrets (key alias `email`, key password the same as the store's), and local builds read
`keystore/release.properties` (git-ignored: `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) when it exists. If the key isn't available locally, build the APK through the
release workflow. The key made for v0.1.0 has the certificate SHA-256
`8d:e5:70:b2:2b:1c:e6:93:bc:56:e5:10:2b:74:af:57:e3:29:52:c2:6e:f0:36:e6:fa:20:3b:7d:87:21:cd:2b`.

## How it fits together

- **The API's types** are in `shared/` (Kotlin, used by the server and the Android app), mirrored by
  `web/src/lib/types.ts` and `desktop/src/api.rs`. Change all of them together.
- **Names:** environment variables start `SAME_`; the session cookie is `same_session`; changes need
  the `X-SAME` header (CSRF); apps come back from the browser to `samemail://signed-in`; the server
  binary is `same-server`; data is `same.db` and `secret.key` in `/data`.
- **Sign-in** (`server/.../auth/`): the break-glass admin (password, always), people with
  passwords, and Authentik (OIDC code flow with PKCE). Apps sign in through the web page
  `/connect-app`, which hands them a one-time code they swap with their PKCE verifier for their own
  token. People added before they've signed in (no password, no OIDC subject) are claimed on their
  first Authentik sign-in by username or email (`UserStore.claimable`), then tied to the subject.
- **Mailboxes** (`server/.../mail/`): `mail_accounts` (managed: an admin's; or personal) and
  `mail_account_members` (who reads it, with their own signature). Passwords are sealed with
  `SecretBox` (AES-GCM, `secret.key`). One pooled IMAP connection per account
  (`MailConnections`); message ids encode the account, folder, UIDVALIDITY and UID. Previews come
  from `BODYSTRUCTURE` plus a partial fetch, cached in the `previews` table. HTML is cleaned by
  `Sanitizer` (jsoup); attachments and inline images are signed links (`Links`), and web images
  load through the signed proxy only when asked. Private mail hosts are refused unless
  `SAME_ALLOW_PRIVATE_MAIL_HOSTS`.
- **The Linux app** follows SuperAwesomeMagicalLinux's colours from
  `~/.local/state/caelestia/scheme.json` (Material names without `#`, and `mode`), live. Its RPM
  declares Fedora's package names (`auto-req = "no"`): the build is on Ubuntu, and the automatic
  scan would require Ubuntu's library versions, which dnf and rpm-ostree can't satisfy. Keep it so.

## Building in a Claude cloud sandbox

- Android SDK: install command-line tools to `/opt/android-sdk`, then
  `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`, and write
  `sdk.dir=/opt/android-sdk` to `local.properties` (git-ignored).
- `repo.maven.apache.org` may answer HTTP 429. Serve Maven Central from Google's mirror with
  `~/.gradle/init.d/central-mirror.gradle`:

  ```groovy
  def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
  settingsEvaluated { settings ->
      [settings.pluginManagement.repositories, settings.dependencyResolutionManagement.repositories].each { repos ->
          repos.removeAll { it instanceof MavenArtifactRepository && it.url.toString().contains("repo.maven.apache.org") }
          def r = repos.maven { url mirror; name "CentralMirror" }
          repos.remove(r)
          repos.addFirst(r)
      }
  }
  ```

- Robolectric downloads Android jars itself. Before running tests, add
  `-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`
  to `JAVA_TOOL_OPTIONS`.
- Docker Hub may answer HTTP 429; pull base images from `mirror.gcr.io/library/…` and tag them.
  Building the image needs the sandbox proxy's CA inside the build: a copy of the `Containerfile`
  that adds `/root/.ccr/ca-bundle.crt` (as `NODE_EXTRA_CA_CERTS` for npm, imported into the JDK's
  `cacerts` for Gradle, plus the mirror init script), built with `--network host`. Don't commit that
  copy. The same goes for test containers (`update-ca-trust` on Fedora).
- The Linux app needs `libgtk-4-dev libadwaita-1-dev libwebkitgtk-6.0-dev libssl-dev`. To look at
  it: `Xvfb :91`, then run it with `DISPLAY=:91 GDK_BACKEND=x11 GSK_RENDERER=cairo
  WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1` and its own `dbus-daemon --session` (in a scratch
  `HOME`), take screenshots with `import -window root`, type with `xdotool` (XTEST, not
  `--window`), and open messages with
  `gdbus call … --method org.freedesktop.Application.ActivateAction open-message "[<'ID'>]" {}`.
  (The WebKit sandbox variable is only for this container; never set it in the app or packages.)
- For a demo mail server: GreenMail standalone (`greenmail-standalone` jar, `-Dgreenmail.setup.test.all
  -Dgreenmail.users=…`), IMAP on 3143 and SMTP on 3025, with `SAME_ALLOW_PRIVATE_MAIL_HOSTS=true`
  on the server.
