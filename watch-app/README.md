# HealthTracker Wear OS App

Custom Wear OS 3+ app for Samsung Galaxy Watch 4 that collects health data via
`androidx.health.services` and POSTs batched samples to the local dev backend at
`http://192.168.1.16:8787`.

## Open in Android Studio

1. Launch Android Studio.
2. **File → Open** → select this `watch-app` folder → OK.
3. When prompted, let Gradle sync and download the SDK components / build tools.
   First sync will take 5–15 minutes depending on connection.
4. Install a **Wear OS 4** SDK image + emulator via **Tools → SDK Manager → SDK Platforms**
   (pick "Android 14 (API 34)" → "Wear OS Small Round" system image).
5. Create a Wear OS AVD via **Tools → Device Manager → Create Device → Wear OS**.

## Run on the emulator

1. Boot the Wear OS AVD.
2. Press the green Run button.
3. Tap "1. Sensors", "2. Background", "3. Activity" one at a time to grant permissions.
4. Tap "Start collecting".
5. Open the Health Services sensor panel (heart icon on the emulator toolbar) to inject
   synthetic HR/steps.
6. Tap "Sync now" to force upload; check the backend logs and `curl http://localhost:8787/v1/samples`.

## Sideload to real Galaxy Watch 4

Assuming your laptop and watch are on the same Wi-Fi (or laptop hotspot):

```
# On watch: Settings > About watch > Software info > tap Software version x7
# Then: Settings > Developer options > ADB debugging + Debug over Wi-Fi
# Note pairing IP:PORT and the 6-digit code

adb pair 192.168.x.x:PAIRPORT
# enter 6-digit code
adb connect 192.168.x.x:CONNPORT
adb devices     # confirm watch shows

./gradlew installDebug   # or hit Run in Android Studio
```

## Configuration

Edit `app/src/main/java/dev/healthtracker/watch/Config.kt`:
- `BACKEND_URL` — your laptop LAN IP + `:8787` for dev, HTTPS URL after deploy.
- `WATCH_TOKEN` — must match the backend's `WATCH_TOKEN` var/secret.
- `DEVICE_ID` — arbitrary string identifying this watch.

## Cleartext HTTP

`res/xml/network_security_config.xml` allows plain HTTP to `192.168.1.16`, `10.0.2.2`
(emulator loopback to host), and `localhost` only. All other domains require HTTPS.
Update the domains list if your LAN IP changes.
