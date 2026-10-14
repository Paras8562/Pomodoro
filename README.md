# Pomodoro – study session planner (Material 3, Jetpack Compose)

Features: session planner (study time -> optional short/long breaks -> auto-built plan),
gradient-capsule timer, subtle per-digit animation, 6 colour themes + Material You ("You"),
Auto/Light/Dark, ticking (Soft/Clock), distinct sounds for focus / short break / long break / done,
volume + vibration, screen stays awake while running.

## Get the APK
### Option A – Android Studio (PC)
1. File > Open > this folder, wait for Gradle sync.
2. Build > Build Bundle(s)/APK(s) > Build APK(s) > "locate" ->
   app/build/outputs/apk/debug/app-debug.apk
3. Copy it to your phone, tap it, allow "Install unknown apps".

### Option B – no PC (GitHub builds it)
1. Create a GitHub repo, upload all files (keep the .github folder) to `main`.
2. Actions tab > "Build APK" > wait ~5 min > download artifact "Pomodoro-APK" > install app-debug.apk.
