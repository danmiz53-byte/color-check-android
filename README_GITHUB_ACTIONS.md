## GitHub Actions build

If your project does **not** include a functional `gradlew` wrapper, the workflow builds using a pinned Gradle version (8.7) directly.

The workflow file is located at:
- `color_check_android_pro/.github/workflows/build-debug-apk.yml`

After a successful run, download the APK from **Actions → run → Artifacts → app-debug**.
