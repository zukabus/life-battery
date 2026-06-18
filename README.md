# Life Battery

An Android app that gamifies daily habits as a battery — drains with bad habits, recharges with good ones.

## Features
- Battery starts at 100%, drains 10% per day automatically
- Customizable drain & recharge actions in Settings
- Home-screen widget shows current battery
- Today's activity log
- Data persists across restarts (carries over day-to-day, floor 0%)

## How to get the APK (no Android Studio needed)

1. Create a free [GitHub account](https://github.com/signup) if you don't have one.
2. Create a new **empty** repo (e.g. `life-battery`). Keep it Public (free Actions minutes) or Private.
3. From this folder (`LifeBattery/`) push the code:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/life-battery.git
   git push -u origin main
   ```
4. Go to the repo on github.com → **Actions** tab → wait ~3-5 min for the build to finish.
5. Click the green build → scroll down → download **LifeBattery-debug-apk**.
6. Transfer the APK to your phone, open it, allow "Install from unknown source", install.

## Re-building after changes
Just `git add . && git commit -m "..." && git push` — GitHub rebuilds automatically.
