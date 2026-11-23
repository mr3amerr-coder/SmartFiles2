# Smart Files

Android file manager and phone optimiser: browse/edit files, junk & duplicate cleaner, recycle bin,
unused-app finder, **phone tweaks**, and WhatsApp sticker re-add. Kotlin + Jetpack Compose, Android 11+.
Fully offline (the app has no internet permission).

## What's new in 1.5
**Phone tab (new)**
- Live gauges for storage, memory and battery (with temperature and health).
- **Boost** (drops background processes of apps you installed), **Clear caches**, and a shortcut to the junk scan.
- **One-tap presets**: Battery saver / Balanced / Snappy, each with **Undo**.
- **Fine-tune**: screen timeout, adaptive brightness + slider, auto-rotate, touch sounds, touch vibration
  (needs "Modify system settings", one tap), and **animation speed** (needs a one-time adb grant, shown in the app).
- Shortcuts to the system screens that matter (battery saver, battery optimisation, data usage, developer options, ...).
- Settings sheet: System/Light/Dark theme, pure-black mode for OLED, wallpaper colours, hidden files.

**Premium look**
- New design system: branded colour palette, rounded cards, real icons (no emoji), colour-coded file types,
  smooth animations, proper edge-to-edge system bars, RTL-ready.
- Files: tappable breadcrumb path, sort direction, rename pre-selects the name, haptic multi-select.
- Clean: storage ring + colour-coded usage bar, results grouped by type with select-all per group,
  Bin merged in as a second page. "To Bin" is the default; "Delete permanently" is an explicit checkbox.
- Apps: real app icons.
- Deleting is instant with an **Undo** snackbar (files and Clean results).

**Efficiency**
- One fast NIO scan pass feeds both the junk scan and the storage breakdown (1 system call per file instead of 3).
- Storage breakdown is cached for 5 minutes instead of re-walking the phone on every visit; live "files checked" counter.
- App-icon, thumbnail and sticker caches; O(1) selection lookups; tabs keep their scroll/search state.
- Release build is minified + resource-shrunk (R8) and signed with a **fixed key**, so each new APK installs
  as an update over the old one. (First time only: uninstall the old build, since earlier ones used a random key.)

## Get the APK
- **Actions tab -> latest "Build APK" run -> Artifacts -> SmartFiles-apk**, or
- push a tag like `v1.5` and download `SmartFiles.apk` from the **Releases** page.

On the phone: allow "Install unknown apps", then grant "All files access" (and "Usage access" for the Apps tab).

## Animation speed (optional, one time)
Android only lets an app change animation speed after this permission is granted from a computer:

    adb shell pm grant com.example.smartfiles android.permission.WRITE_SECURE_SETTINGS

The Phone tab shows this command with a Copy button.

## Build locally
Open the folder in Android Studio, or run `gradle assembleRelease` (Gradle 8.9, JDK 17).

## Layout
`app/src/main/java/com/example/smartfiles/`: `MainActivity` (shell) · `Theme` · `Ui` (shared components) ·
`FilesScreen` · `CleanScreen` · `Scanner` (scan + app state) · `FileOps` (files, Bin, volumes) · `AppsScreen` ·
`PhoneScreen` + `Tweaks` · `Stickers` + `StickersUi`.
