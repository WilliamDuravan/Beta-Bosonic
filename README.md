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

## Raw video (v0.7)
"Raw rec" writes lossless Y16 to Download/BosonThermal/ as `<name>.y16` (concatenated
little-endian 16-bit frames, no headers) plus `<name>.csv` (timestamps, drop info).
~590 MB/minute at 60 fps. Convert on a PC with `tools/y16_tools.py` (info, mkv, preview, tiff),
or directly: `ffmpeg -f rawvideo -pixel_format gray16le -video_size 320x256 -framerate 60 -i REC.y16 -c:v ffv1 out.mkv`

## Tone curve (v0.8)
"Curve: on" shows a panel with the live histogram and three handles on the current min..max range:
T (end of the dark tail), M (input value shown as mid-grey), S (start of the bright tail).
Tails roll off smoothly instead of clipping. Double-tap the panel or press "Reset curve" to reset.
Applies to preview, snapshot PNG and MP4; raw data is never altered. Settings persist.
