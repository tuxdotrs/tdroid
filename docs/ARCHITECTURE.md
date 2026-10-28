# Architecture

How Tdroid is put together, for anyone working on it.

An Android home-screen launcher that runs Nix-on-Droid under proot and acts
as its Wayland compositor. It is a fork of omalauncher, which ran Arch Linux ARM. The home screen (widgets, pinned apps, the bar
and its app list) is Jetpack Compose; every Linux window opens as its own Android task,
so it gets a Recents card and behaves like a separate app.

Linux is optional. Without it the launcher still has Omarchy's themes and
wallpapers, Android apps from the bar and the widgets that don't run commands.
Everything else is offered as an install from settings › linux and wherever
it appears: Linux apps, terminal widgets, coding agents, Nix and Files.
Widgets that need Linux (`Program.needsLinux`: cmd/stream sources, `Terminal`,
run/terminal/app actions, `requires`) show as placeholders in their cells until
it's installed. Removing Linux deletes `files/usr` (Nix-on-Droid's installation
dir) and `files/home` (the guest's home) only.

Launcher data that the guest also uses lives outside the rootfs and is bound
into it by proot (`LauncherData.kt`), so installing or removing Linux never
touches it: `files/omarchy` is `~/.local/share/omarchy` and `files/widgets` is
`~/.config/tdroid/widgets`.

## Source layout

- `app/src/main/cpp/wlbridge.c`: JNI for unix sockets with fd passing, shm
  mapping, per-surface pixel stores, and compositing them into a window's
  `ANativeWindow`
- `wayland/`: the Wayland server in Kotlin (wl_compositor, wl_shm,
  wl_subcompositor, wl_seat, wl_output, xdg_wm_base, xdg-decoration, and
  text-input-v3 for where a client's text cursor is) and
  `WindowRenderer`, which draws one window into its activity's SurfaceView
  (or, for a widget's `Terminal`, into a TextureView in the tile)
- `linux/`: proot, the Nix-on-Droid setup and its update steps, the Nix config
  (`NixConfig`), coding agents, and the bridges to Android (audio, URLs, /proc)
- `ui/`: the Compose home screen, the bar and its app list, settings and Files
- `widgets/`: the widget engine; the language itself is in the `owl` module.
  A widget's state and sources live in its `WidgetInstance`, which runs while
  its page or a neighbour is on screen, but the pager composes only the
  current page: a node drawn in Kotlin loses its `remember` state when its
  page is swiped away, and keeps anything lasting in the instance
- `theme/`: Omarchy themes, applied to the launcher and the guest
- `upgrade/`: how installs follow app updates (see below)

## Rendering

A client's commit copies only the damaged rectangles of its shm buffer into
the surface's pixel store, outside the server lock, and hands the buffer back
right away. Each window's `WindowRenderer` runs on its own thread: at vsync it
snapshots the surface tree, redraws only the dirty region of the SurfaceView
(everything, if a surface moved or appeared), posts, and then sends the frame
callbacks. Hidden windows get no frame callbacks, so their clients stop
drawing.

## The Linux system

Nix-on-Droid, from the `userspace` submodule with `patches/userspace` applied (see the
README). The app downloads its bootstrap zip and unpacks it into `files/usr`, recreating
the links in `SYMLINKS.txt` and the modes in `EXECUTABLES.txt`. proot runs it the way
Nix-on-Droid's own login does, but without its `login` script, which every
`nix-on-droid switch` rewrites: Android's root stays the guest's, with `files/usr`'s
`nix`, `bin`, `etc` and `usr` bound over `/nix`, `/bin`, `/etc` and `/usr`. The guest
runs as the app's user, `nix-on-droid`, with its home at `files/home`, the same path
inside and out. Every command first sources `nix-on-droid-session-init.sh`, as
`login-inner` does. First boot is `login-inner` with `USE_FLAKE=1`, which makes
`~/.config/nix-on-droid` from `userspace-template` and switches to it.

The packages are the user's flake; the launcher only edits `tdroid-packages.json`
in it (coding agents) and runs `nix-on-droid switch` (`NixConfig`). Linux apps render in
software: omalauncher's GPU drivers came from Arch packages and aren't ported.

## Updates

The app writes into the rootfs and keeps state of its own, so an update must
reach existing installs without breaking them. There are four mechanisms:

- **Rootfs steps** (`linux/Bootstrap.kt`): an ordered list of idempotent steps
  (`/etc` configuration, packages, the keymap, cliamp).
  Each one's id is recorded once it succeeds (`files/rootfs-steps.json`). To
  change what a step does on existing installs, give it a new id, for example
  `packages-1` → `packages-2` after changing the package list. A fresh install
  runs every step in the background, with progress in settings › linux and
  the notification. After an update, new offline steps run before Linux
  starts, and steps that need the network run in the background. Their progress and failures show in settings, and they retry on
  the next start.
- **Launcher-provided files** (`upgrade/ManagedFiles.kt`): shims, desktop
  entries, default configs, default widgets. Every file written is recorded
  with its hash: the launcher's own (default widgets) in
  `files/launcher-managed.json`, the guest's in `files/managed.json`, which
  only syncs while Linux is installed and is forgotten with it.
  - `Owned` files (plumbing) always match the APK.
  - `Seeded` files are defaults the user may edit. They are updated only while
    unchanged since we wrote them, and never brought back once deleted.
  - Files dropped from the APK are deleted if they're unchanged.
  - The sync runs on every start and only hashes files when nothing changed.
- **Saved data** (`upgrade/Versioned.kt`): preferences and JSON files are
  stored as `{"v": N, "data": …}`. A format change bumps `N` and adds a
  migration step to the reader. Entries are parsed one at a time, so a bad
  one is skipped instead of losing the rest.
- **Widgets**: `Compiler.LEVEL` in the owl module is the language version.
  Widgets that need a newer one declare `meta { owl: N }`, and store entries
  carry it. An older launcher then shows "needs a newer launcher" instead of
  failing. Bump the level with every addition a widget could depend on. The
  store's `index.json` format number only changes for incompatible changes.

**System updates** aren't done by the launcher: the user runs `nix flake update` and
`nix-on-droid switch` in `~/.config/nix-on-droid`.

Pinned versions: `ThemeStore.OMARCHY_REF` (the Omarchy commit) and
`BOOTSTRAP_URL` in `Bootstrap.kt`. Changing the first makes every install
download the new themes on its next start (`ThemeStore.ensureRelease`, with or
without Linux); rerun `scripts/fetch-theme-colors.sh` with it, since first run
fetches only the picked theme's colors and first background by the names that
script bundles. The second only reaches new installs; existing ones update
through Nix.

Guest processes die with the app (`--kill-on-exit`), so the Wayland protocol,
the socket and the guest environment carry no state across an update.
