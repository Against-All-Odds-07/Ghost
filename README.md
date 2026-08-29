# 👻 Ghost

**Android target app for the Ghost remote-control .**

Ghost runs on the Android device you want to access and works with **Ghoston**, the controller application, over **Tailscale**.

---

## Features

* Remote screen sharing
* Remote touch and gesture control
* Keyboard and text input
* File access and transfer
* SMS and messaging support
* Clipboard sharing
* Device information
* Battery and storage information
* Device pairing and authentication

---

## How It Works

```text
┌──────────────┐
│   Ghoston    │
│  Controller  │
└──────┬───────┘
       │
   Tailscale
       │
       ▼
┌──────────────┐
│    Ghost     │
│    Target    │
└──────────────┘
```

Ghoston controls the device running Ghost through the Tailscale network.

Communication between the applications uses the Ghost WebSocket protocol.

---

## Requirements

* Android device
* [Tailscale](https://tailscale.com/)
* Ghost installed on the target device
* Ghoston installed on the controller device
* Required Android permissions

Some features depend on the Android version and the permissions available on the device.

---

## Installation

Download the latest APK from the [Releases](../../releases) page.

Install **Ghost** on the Android device you want to control.

Make sure:

1. Tailscale is installed and connected.
2. Required Android permissions are enabled.
3. Ghost is running.
4. Ghoston is installed on the controller device.

Then pair the devices through Ghoston.

---

## Build

Clone the repository:

```bash
git clone https://github.com/YOUR_USERNAME/Ghost.git
cd Ghost
```

Build the debug APK:

```bash
./gradlew assembleDebug
```

The APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```text
Ghost/
├── app/
│   ├── src/
│   └── build.gradle.kts
├── gradle/
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── README.md
```

---


---

## Permissions

Depending on the enabled features, Ghost may require access to:

* Screen capture
* Accessibility
* Storage / files
* SMS
* Notifications
* Clipboard

Permissions are used only for their respective functionality.

---

## Security

Ghost is intended for **authorized access to devices you own or are permitted to manage**.

Use the application only on trusted devices and networks.

---

## Related Project

### Ghoston

Android controller application used to connect to Ghost.

```text
Ghoston → Tailscale → Ghost
```

---

## Releases

APK releases are available on the repository's [Releases](../../releases) page.

---

## License

See the [`LICENSE`](LICENSE) file for details.
