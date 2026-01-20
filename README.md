# ColorCheck Pro (Android MVP)

This is a Kotlin + CameraX Android Studio project for a friendly **"Simple"** flow and a hidden **"Pro"** (Advanced) flow:

- Camera inside the app (CameraX)
- Import from gallery
- Tap-to-measure with robust sampling (window sampling + highlight/shadow downweight + trimmed mean)
- Advanced:
  - Calibrate: tap a patch then choose White/Gray/Black (any order; 2+ patches works)
  - Free region (Lasso): draw a free-shape region and measure it
- Output: HEX + RGB + Lab D50 + Quality score

## How to run

1) Open the folder `ColorCheckPro` in **Android Studio**.
2) Let Android Studio **sync Gradle**.

### If Gradle sync fails with "GradleWrapperMain" (missing wrapper jar)
Some environments require regenerating the wrapper jar.

Option A (recommended):
- Android Studio > **Settings** > **Build, Execution, Deployment** > **Build Tools** > **Gradle**
- Set **Gradle JDK** to your installed JDK 17
- Set **Use Gradle from**: **"Gradle distribution"** (embedded) and sync.

Option B:
- Create a new empty Android project in Android Studio
- Copy the `app/src/main/java` and `app/src/main/res` from this project into your new project
- Copy dependencies from `app/build.gradle.kts`

## Notes
- The pipeline currently measures on the captured or imported still image (not on live preview).
- Online AI hooks are planned (send small ROI + optional mask to a server), but not enabled in this MVP.
