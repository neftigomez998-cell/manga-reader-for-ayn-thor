# Thor Manga

A manga reader for the **AYN Thor** that uses both screens. It also works on a regular one-screen Android device.

## Reading modes

| Mode | Top screen | Bottom screen |
|---|---|---|
| **Split** (default) | Top part of the page | The rest of the same page. One page fills both screens, sized so it lines up physically across them. |
| **Page** | The whole page (pinch to zoom, double-tap for fit-width) | Controls, plus a preview of the next page |
| **Strip** | Continuous vertical scroll for webtoons... | ...that keeps going onto the bottom screen |

In Split and Strip, tap the **middle** of either screen to show the controls on the bottom screen.

## Library

* On first launch, pick the folder where you keep manga. It can be on internal storage or an SD card.
* **.cbz / .zip**, **.cbr / .rar** (RAR 4 and older; RAR5 not yet) and **folders of images** (jpg, png, webp, gif, avif, heic) all work. Sub-folders show up as folders.
* Your page is saved per book. The list shows "Page 12 of 180", or "Finished".
* You can also open a .cbz straight from a file manager with "Open with → Thor Manga".
* Not supported: .pdf, .epub, RAR5 archives.

## Controls

| Input | Reading | Library |
|---|---|---|
| Tap left / right third of the page | Turn page (direction follows Manga/Comic setting) | — |
| Swipe left / right (Split also: swipe up / down) | Turn page | — |
| D-pad ← → | Turn page (Manga: ← is next) | ← back · → open |
| D-pad ↑ ↓ | Scroll a tall page / strip, or turn page | Move selection |
| A / R1 / R2 / Vol− | Next page | Open |
| L1 / L2 / Vol+ | Previous page | Jump up 8 |
| B / Back | Hide controls, then go back to the library | Up one folder / quit |
| X | Switch mode (Split → Page → Strip) | Swap screens |
| Y / Start | Show or hide controls | Change folder |
| Select | Swap screens (Page mode) | Swap screens |

**Manga ← / Comic →** sets the reading direction (right-to-left is the default).

## Building the APK

The project has no third-party libraries, just plain Android (Java, min Android 8, target Android 14).

### Option A: GitHub (no software to install)
1. Create a new GitHub repository and upload everything in this folder, including the hidden `.github` folder.
2. Open the **Actions** tab. The "Build APK" workflow runs on every push. You can also start it with *Run workflow*.
3. When it finishes (about 3–5 min), download **ThorManga-apk** from the run page and unzip it.

### Option B: Android Studio
1. *File → Open* this folder and let Gradle sync.
2. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
3. The APK is in `app/build/outputs/apk/`.

### Installing on the Thor
Copy the APK to the Thor and open it in a file manager. Allow "install unknown apps" when it asks.
Or use `adb install ThorManga.apk`.

## How the dual screen works (for tinkerers)
`MainActivity` starts on the main display. It then looks for the other display:
* If that display is a presentation display, it shows a `Presentation` there.
* Otherwise it launches `SecondScreenActivity` onto it with `ActivityOptions.setLaunchDisplayId`.

`Session` holds all the shared state. It decides each screen's role by size: the bigger screen gets the page and the smaller one gets the controls. Page layout is computed in inches from each display's DPI, so a page split across both screens stays the same physical width.
