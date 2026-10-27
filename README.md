# Ascend

A themed "system interface" for Android that works **without root**. Built for the Nothing Phone (3a), works on any phone with Android 8 or newer (best on Android 13+).

## What it does

| Module | What you get |
|---|---|
| **Home screen** | Clock, a "status window" (battery, day progress, notifications), a 5-app dock and an app drawer with restyled icons (Line / Orb / Original). Swipe up for apps, swipe down for the shade, double-tap to lock, long-press empty space for settings. |
| **Keyboard** | Fully themed keyboard for every app (WhatsApp, Chrome, …) with glowing glass keys, a key preview bubble and a suggestion bar (German + English, learns your words, off in password fields). QWERTZ or QWERTY, number row, symbols, emoji page, long-press for ä ö ü ß é, double-space for a full stop. |
| **Notifications** | New notifications slide in as themed notices, plus an edge glow around the screen. |
| **Shade & volume** | Swipe down on the status bar for a themed shade: Wi-Fi, Bluetooth, torch, Focus (DND), rotation, screenshot, lock, power menu, brightness, media controls and your notifications. Volume keys show a themed slider. Glass effect blurs the screen behind the shade (Android 12+). Optional HUD frame and "system online" unlock animation. |
| **Live wallpaper** | Animated particles and grid in your theme. Android reads its colours, so the system accent (quick settings, Chrome, Google apps) follows your theme. |
| **Quick tile** | Cycle themes from quick settings. |

Themes: **Monarch** (black · violet · white), **Monarch Light** (white · violet · black), **Awakened** (blue), **Red Gate** (crimson), **Shadow** (graphite).

---

## 1 · Build the APK (pick one)

**Already built:** every push to `main` publishes the newest APK at
<https://github.com/RustamUR-IT/Ascend/releases/latest/download/Ascend.apk>. Open that link on your phone to download it.

### Option A: GitHub (no software to install)

1. Create a free account at github.com and make a **new repository** (it can be private).
2. On the repository page choose **Add file → Upload files** and drag in everything from this folder.
   *Browsers often skip the hidden `.github` folder.* If the **Actions** tab shows no workflow afterwards, choose **Add file → Create new file**, name it `.github/workflows/build.yml` and paste in the contents of that file from this folder.
3. Open the **Actions** tab → **Build Ascend APK** → **Run workflow**. It takes about 4 minutes.
4. Open the finished run and download **Ascend-apk** from the bottom of the page. Unzip it to get `Ascend.apk`.

### Option B: Android Studio on a computer

1. Install Android Studio, then **File → Open** and choose this folder. Let it finish syncing.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. The file appears in `app/build/outputs/apk/debug/app-debug.apk`.
   (Or plug in your phone with USB debugging on and press ▶ Run to install directly.)

## 2 · Install on the phone

1. Copy the APK to the phone (Google Drive, USB, email to yourself…) and tap it.
2. Allow "Install unknown apps" for the app you opened it from.
3. Play Protect may warn that it doesn't recognise the app. Tap **More details → Install anyway**. This is normal for apps you build yourself.

Updating later: build again and install over the top. Your settings stay, because every build uses the same signing key (`app/ascend.jks`).

## 3 · Set it up (in this order)

Open **Ascend** from the app drawer. Each module card has buttons that take you to the right setting.

1. **Theme**: pick one at the top.
2. **Live wallpaper**: tap *Set wallpaper* → *Set wallpaper* → *Home and lock screen*.
3. **Keyboard**: *Enable*, then *Switch to it*.
4. **Notifications**: grant *Notification access*. Then, in Nothing's Settings → Notifications → App notifications, turn off *Pop on screen* for chatty apps like WhatsApp so you only see Ascend's version.
5. **Shade & volume**: open *Accessibility* → *Ascend Shade* → On.
   If the switch is greyed out: *App info* → **⋮** (top right) → **Allow restricted settings**, then try again. Also tap *Brightness access* so the brightness slider and rotation toggle work.
6. **Home screen**: *Set as home app* → choose *Ascend Home*. To pin apps to the dock, long-press them in the drawer.
7. Optional: *Add tile* for the theme switcher in quick settings.

Then finish the rest of the system with the tips shown at the bottom of the app (dark theme, force-dark, WhatsApp chat theme, Chrome theme).

## Good to know

- **Other apps' insides can't be restyled without root.** WhatsApp chats and web pages keep their own design; the keyboard, pop-ups, shade, volume panel, home screen and system accent around them are what Ascend changes.
- **Nothing's own panel still works**: swipe the *other* half of the status bar (configurable), or tap *System panel* at the bottom of the Ascend shade.
- **Battery**: the live wallpaper only animates while visible. Expect a few percent per day overall.
- **Privacy**: Ascend has no internet permission. Notifications are only drawn on screen; nothing is stored or sent.
- **Turning things off**: every effect has a switch in the app. To stop everything, set another home app, keyboard and wallpaper and switch Ascend off in Accessibility.

## If something breaks

After a crash, the Ascend screen shows a **Last crash** card. Tap **Share report** and send the text to whoever maintains the app.

## Credits

- Fonts: [Rajdhani](https://github.com/google/fonts/tree/main/ofl/rajdhani) and [Orbitron](https://github.com/google/fonts/tree/main/ofl/orbitron), SIL Open Font License (see `app/src/main/assets/fonts`).
- Word lists: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave, CC BY-SA 4.0. German noun capitalisation checked against the [igerman98 / frami](https://github.com/LibreOffice/dictionaries/tree/master/de) Hunspell dictionary.

## Project layout

```
app/src/main/java/com/ascend/ui/
  Prefs.kt            themes + settings
  Ui.kt               shared drawing helpers (HUD panels, icons, bars)
  MainActivity.kt     the control centre
  LauncherActivity.kt home screen + app drawer
  AppRepo.kt          app list + icon restyling
  KeyboardService.kt  keyboard typing logic
  KeyboardView.kt     keyboard drawing + touch
  Suggest.kt          word suggestions + learned words
  AscendApp.kt        app start-up, fonts, crash log
  NotifyListener.kt   reads notifications
  Overlays.kt         pop-ups, edge glow, HUD frame, unlock splash, volume panel
  ShadeService.kt     accessibility service (swipe zone, volume keys, effects)
  ShadeView.kt        the pull-down shade
  AscendWallpaper.kt  live wallpaper
  ThemeTile.kt        quick settings tile
```

To add your own theme, add an entry to `Themes.all` in `Prefs.kt`.
