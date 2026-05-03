# Permissions

MassDroid requests permissions at runtime when needed. Proximity-related permissions are requested only when you enable Follow Me.

| Permission | Why |
|---|---|
| Internet | Connect to your Music Assistant server |
| Foreground Service (Media Playback) | Keep media controls and playback active in the background |
| Foreground Service (Connected Device) | BLE scanning for proximity room detection |
| Bluetooth Scan / Connect | Discover nearby BLE devices for room fingerprinting |
| Fine Location | Required by Android for BLE scanning |
| Activity Recognition | Step detector for motion-gated proximity scanning |
| Post Notifications | Show playback controls, proximity room alerts, and update prompts |
| Wake Lock | Keep Sendspin audio streaming while screen is off |
| Battery Optimization Exemption | Reliable background playback and proximity detection |
