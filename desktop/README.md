# SuperAwesomeMagicalEmail for Linux

The desktop app for your SuperAwesomeMagicalEmail server: native, written in Rust with GTK 4 and
libadwaita, with messages drawn by WebKitGTK (locked down: no scripts, nothing stored). It talks
only to your server, which holds the mailboxes. The app never sees a mailbox password.

- **Sign in** with your server's address, then **Sign in with Authentik** (it opens your browser,
  which hands the sign-in back to the app at `samemail://signed-in`), or a password where the
  server allows one.
- **Every mailbox** your server gives you, laid out as the web app: **All inboxes** together, then
  each account's folders with their unread counts; the list (with search and paging); and the
  message beside it. On a narrow window the folders slide in and the list and message take turns.
- **Reading**: HTML mail on its own white page and plain text in the app's own colours. Images from
  the web stay hidden until you ask (or always show them, in Preferences), so senders can't tell
  when you read. Links open in your browser; mail links start a message here. Save attachments, or
  a message's original (.eml).
- **Doing**: reply, reply all, forward, archive, delete, mark unread, star, move, junk, unsubscribe,
  and drafts to finish.
- **Writing** in a window of its own: from any of your mailboxes, addresses suggested as you type,
  files attached (or dropped on it), Ctrl+Enter to send. Closing it with something written offers
  to keep it in Drafts.
- **New mail notifications** with **Archive** and **Mark read** buttons, through the desktop's
  notification service (SuperAwesomeMagicalLinux's shell, GNOME's, KDE's). With **Keep running in
  the background** on, it tells you with the window closed too, and starts after you sign in to the
  desktop (a systemd user timer, so it works on Hyprland as on GNOME).
- **The default mail app**: `mailto:` links open a message here (choose it as the default email app
  in your desktop's settings, or `xdg-mime default io.github._603madison.SuperAwesomeMagicalEmail.desktop x-scheme-handler/mailto`).
- **The family's look**: Lexend, rounded pills and cards, the themes Aurora (purple and green, the
  default), Violet, Ocean and Ember, light or dark (or as the system is), and **Bold text**.
- **On SuperAwesomeMagicalLinux** it starts in the **Desktop** theme: the shell's own colours
  (`~/.local/state/caelestia/scheme.json`), light or dark as the shell is, changing as the shell's
  scheme or wallpaper changes it.
- Shortcuts (Ctrl+? lists them): Ctrl+N write, Ctrl+F search, F5 check for mail, and for the
  message open R reply, A reply all, F forward, E archive, Delete, S star, Shift+U mark unread,
  Shift+I show images.

## Installing

From the repository's **Releases**:

- **SuperAwesomeMagicalLinux** (and other Fedora Atomic desktops):
  `rpm-ostree install ./SuperAwesomeMagicalEmail-Desktop-vX.Y.Z-x86_64.rpm`, then restart.
- **Fedora 40 and later:** `sudo dnf install ./SuperAwesomeMagicalEmail-Desktop-vX.Y.Z-x86_64.rpm`
- **Ubuntu 24.04 and later** (and Linux Mint 22, Pop!_OS 24.04, Debian 13):
  `sudo apt install ./SuperAwesomeMagicalEmail-Desktop-vX.Y.Z-amd64.deb`

dnf and apt bring in what it needs: GTK 4.14, libadwaita 1.5 and WebKitGTK 6. Older systems
(Ubuntu 22.04 and what's based on it) have too old a GTK. x86-64 only for now.

It's then in your applications as **SuperAwesomeMagicalEmail**; its settings (and sign-in, readable
by you only) are in `~/.config/SuperAwesomeMagicalEmail`.

## Building

With a current stable Rust and, on Ubuntu, `libgtk-4-dev libadwaita-1-dev libwebkitgtk-6.0-dev
libssl-dev`:

```sh
cargo run                  # run it
cargo test
cargo build --release
cargo deb --no-build       # target/debian/*.deb (cargo install cargo-deb)
cargo generate-rpm         # target/generate-rpm/*.rpm (cargo install cargo-generate-rpm)
```

The release workflow's `desktop` job builds both packages on Ubuntu 24.04 and adds them to each
GitHub release. The RPM names Fedora's packages as what it needs (not the libraries the Ubuntu
build links), so dnf and rpm-ostree can install it. The version in `Cargo.toml` is the project's
version (`versionName` in `android/build.gradle.kts`).

How it fits together: `src/api.rs` (the server's API, as the web app and phone app use it),
`src/ui/mod.rs` (the window, new mail and notifications, links the app is opened with),
`src/ui/login.rs`, `src/ui/mail.rs` (folders, the list and the message), `src/ui/compose.rs`,
`src/ui/prefs.rs` and `src/ui/theme.rs` (the themes, and following SuperAwesomeMagicalLinux's).
