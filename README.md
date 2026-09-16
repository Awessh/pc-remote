# PC Remote Control — Android to Windows

Control your Windows PC remotely from your Android phone via your local Wi-Fi network.

## Features

- Sleep/Hibernate/Shut down the PC
- Menu listing all installed applications (Start Menu shortcuts) with one-tap launch
- Touchpad: 1 finger to move the cursor and click, 2 fingers to scroll, dedicated left/right click buttons
- Smartphone keyboard transmitted to the PC in real time
- Windows notifications sent to the phone (requires the `winsdk` module, see below)

## Architecture

```
PCRemote/
├── windows-server/ Python server to run on the Windows PC
└── android-app/ Android Studio project (Kotlin)
```

Communication is via **WiFi**, using a TCP socket on port 58432

(commands + responses in JSON) and a UDP port 58433 used only for
the Automatic discovery of the PC on the local network.
