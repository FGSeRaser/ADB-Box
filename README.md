# ADB Box

Android-App von **[FGSeRaser](https://github.com/FGSeRaser)**, um andere Geräte (z. B. ein altes Handy als Server) direkt vom Handy aus zu steuern – auch unterwegs über Tailscale.

- **Befehle & Konsole** über SSH (Termux) oder die ADB-Shell, mit eigenen Schnellbefehlen
- **Dateien** per SFTP: hoch- und herunterladen, APKs auf dem Handy oder per ADB auf dem Gerät installieren
- **Apps** per ADB: Liste, starten, sichern (APK aufs Handy), deaktivieren, deinstallieren, APK installieren
- **Bildschirm** spiegeln und steuern wie scrcpy (nutzt den freien scrcpy-Server von Genymobile, wird beim ersten Mal geladen)

## Einrichtung (z. B. Galaxy S8+ mit Termux)
1. Termux: `pkg install openssh`, `passwd`, `sshd` (Port 8022)
2. ADB: USB-Debugging an, nach jedem Neustart einmal per Kabel `adb tcpip 5555`
3. In der App ein Gerät mit der Tailscale- oder WLAN-Adresse anlegen

Download: [Releases](https://github.com/FGSeRaser/ADB-Box/releases/latest)
