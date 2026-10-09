# Ollama Desk Remote

An Android app for using [Ollama Desk](../ollama-desk) from your phone on your home network. You can chat with your computer's local models (with streaming and agent mode), approve or deny the agent's actions, manage models, and run scheduled tasks.

Kotlin and Jetpack Compose with Material 3, in a dark theme with an amber accent (#e1a34f). Android 8.0 or newer.

## Building

You need JDK 17 and the Android SDK. With Android Studio, open this folder and press Run. From the command line on Arch:

```bash
sudo pacman -S jdk17-openjdk
# Android SDK: install Android Studio, or the AUR package android-sdk-cmdline-tools-latest, then:
#   sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # wherever your SDK lives

./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with your debug key so it installs straight away. Use your own key if you share the app.

## Pairing

1. On the computer: **Ollama Desk → Preferences → Phone access**, turn on *Allow phone access*, then choose **Pair a phone**.
2. On the phone: open the app and tap **Scan QR code**.

The code works once, for 10 minutes. To remove a phone, use the trash button next to it on the computer, or *Unpair this phone* in the app's menu.

## Using it

- **Chats:** your chats from the computer, newest first. Tap to open, long-press to delete. New chats appear on the computer too.
- **Agent mode:** the robot button in a chat. When it's on, the model can run commands, use files and read the web on the computer. Anything that needs approval pops up on the phone with the exact command or change; destructive actions have a red *Allow*. With the app in the background, a notification brings you back.
- **Models:** choose which model new chats use, see what's loaded and how much sits on the GPU, unload, delete, and download with progress.
- **Tasks:** your scheduled tasks, with their next run and last result. Pause or resume them, *Run now*, or open their results.
- **Voice:** when the message box is empty, the microphone button records you, up to 2 minutes. Tap ✓ when you're done and the text lands in the message box, so you can check or edit it before sending. The speaker under a reply reads it aloud; tap again to stop.
  - Both use the computer's own voice setup (whisper.cpp and Piper), so voice must be set up there first: Ollama Desk → Preferences → Voice.
  - Audio goes only to your computer over the encrypted connection, and is deleted as soon as it's processed. No Google speech services are involved, so it works on LineageOS and GrapheneOS.

## Security

- **Encryption and pinning:** the connection is TLS. The app trusts exactly one certificate, the one whose SHA-256 fingerprint was in the QR code. A different machine on the network can't pretend to be your computer, and nothing depends on certificate authorities.
- **Tokens:** pairing gives this phone its own random token. The computer stores only its hash. The phone keeps it in app-private storage, with backups and device transfer switched off so it never leaves the phone.
- **Network:** the computer refuses connections from outside private network ranges, and the app only ever talks to the addresses from the QR code.

## Limits

- **Network:** home network only. Away from home it can't connect, by design.
- **Background:** Android may pause the connection when the app has been in the background for a while. It reconnects when you open it. An approval nobody answers within 5 minutes is refused.
- **Computer-only features:** attachments, and creating or editing scheduled tasks, live on the computer for now.
