# Follow Me

Walk between rooms and your music follows you. MassDroid uses BLE fingerprinting to detect which room you are in and automatically transfer playback or notify you to switch speakers.

## Features

- **Room Detection**: scans nearby Bluetooth devices and compares the live BLE anchor snapshot against calibrated room fingerprints using vector k-NN room-fit scoring. For distinct locations, Wi-Fi BSSID or SSID matching can be used as an alternative to BLE.
- **Per-Room Configuration**: assign a Music Assistant player to each room, set a preferred playlist for auto-play, configure volume level, and toggle shuffle.
- **Calibration Wizard**: walk through each room while the app collects BLE sampling windows, builds anchor fingerprints, and computes beacon profiles with quality assessment.
- **Time Schedule**: set active days and times so proximity detection only runs when you want it.
- **Auto-Transfer**: optionally transfer the queue automatically without notification when you change rooms.
- **Screen-Off Detection**: works with screen off using OS-managed PendingIntent BLE scans.
- **Motion Gating**: significant motion and step detection trigger scans only when you are actually moving.

Requires Android 12+ with Bluetooth support.

## Room Detection Tips

To get reliable room detection, treat each room like a BLE fingerprinting problem, not just a "find the strongest beacon" problem.

- **Calibrate inside the room, not near the doorway**: doorways blend adjacent-room fingerprints and make transitions less stable.
- **Low signal can still be useful**: a room can have weak BLE beacons and still be detectable if the overall RSSI pattern is consistent and different from nearby rooms.
- **Common beacons are fine**: the same TV, speaker, or router can appear in multiple rooms. What matters is that the RSSI pattern changes between rooms.
- **Prefer stationary devices**: TVs, speakers, consoles, routers, and smart home hubs are good anchors. Phones, watches, earbuds, and other personal devices are intentionally ignored.
- **Private-address devices are not automatically bad**: some useful devices advertise with private BLE addresses. Use the inspection tools to see what the app is actually using or ignoring.
- **Use `STRICT` as the default**: use `NORMAL` only for genuinely weaker rooms that need a more forgiving BLE policy.
- **Use Wi-Fi override only for separate locations**: Wi-Fi BSSID or SSID matching is best for distinct places like `Home`, `Office`, or a detached space.

## Tools

- **Calibration Data**: shows the saved room fingerprint, anchor profiles, sample count, and whether the room currently looks `Good` or `Weak`.
- **Recalibrate**: rebuilds the room fingerprint from fresh BLE scans.
- **Inspect BLE**: runs a high-accuracy BLE scan and shows which devices Follow Me would use as room anchors, which stable devices are visible but not profiled, and which devices are ignored.
- **Detection Mode**: `STRICT` requires a cleaner BLE match. `NORMAL` is a fallback for weaker rooms.
- **Detection Tolerance**: global slider that controls how much signal variation is accepted.
- **Wi-Fi Override**: lets a room match by Wi-Fi `BSSID` or `SSID` instead of BLE anchors.

## Troubleshooting

- If a room looks good in real life but still shows `Weak`, inspect whether it has a low-signal but structured fingerprint rather than simply not enough strong beacons.
- If two nearby rooms confuse each other, recalibrate both away from the doorway and compare their `Inspect BLE` results.
- If a remote location keeps false-triggering from home, store its Wi-Fi AP and enable `Stick to connected Wi-Fi AP`.
- If detection feels slow during movement, test with the screen off as well as screen on.
