# Boson USB Diag (Level 0)

Tiny diagnostic app: lists USB devices, requests permission, and dumps the full
descriptor tree (interfaces, endpoints, UVC formats/frames/fps, extension units,
CDC/vendor serial interfaces). It tells us whether Y16 is advertised and how the
Boson's control channel is exposed.

## Build (GitHub Actions)
1. Create a new GitHub repo and push this folder to it.
2. Open the Actions tab -> "Build debug APK" -> wait for green.
3. Download the `BosonDiag-debug-apk` artifact, unzip, install the APK
   (allow "install unknown apps" for your browser/file manager).

## Build (Android Studio)
Open the folder, let it sync, Run. (Gradle wrapper is not included; Studio makes one.)

## Use
1. Plug the Boson in via the Samsung EE-UN930 adapter, open the app.
2. Tap **Grant USB permission** and accept.
3. Tap **Claim test** (also tries every alt setting on the video interface).
4. Tap **Copy** or **Share** and send the report back.
