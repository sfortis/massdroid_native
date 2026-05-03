# Sendspin & Acoustic Calibration

Sendspin turns your phone into a Music Assistant player. Audio is streamed over WebSocket, decoded on-device, and played through the phone speaker, headphones, or Bluetooth output.

## Phone as Speaker

- Works solo or grouped with other Music Assistant players.
- Streams Opus or FLAC over WebSocket.
- Smart mode can switch audio format based on network conditions.
- Streaming status shows sync graph, output latency, network mode, and static delay control.

## Acoustic Calibration

When the phone is used as a player, output buffers, DAC, drivers, Bluetooth codecs, and speaker DSP add extra delay. MassDroid measures this delay directly using the phone microphone and corrects for it per route.

Open the Sendspin local player, tap the 3-dot menu, and open **Player Settings**.

- **Phone speaker calibration** measures the internal speaker path and acts as the baseline for Bluetooth calibration.
- **Bluetooth device calibration** is available while a Bluetooth output is connected. Each Bluetooth device gets its own correction value.

## How Calibration Works

- The phone plays short 1 kHz test tones through the active output route.
- The microphone records the response.
- A native C++ DSP pipeline detects the tone onset using SNR thresholding.
- Round-trip time is computed from multiple tone bursts and averaged.
- Quality is graded as Good, Fair, or Weak based on consistency.
- The correction is stored per route.

## Running A Calibration

- If music is playing, the app will offer to pause automatically.
- For Bluetooth, calibrate the phone speaker baseline first with Bluetooth disconnected.
- Set media volume around 50 to 70 percent.
- Keep the room quiet while the tones play.
- For Bluetooth, place the phone close to the speaker.
- If calibration reports Weak quality, move the phone closer, raise media volume, reduce background noise, and retry.

## Privacy

The microphone is used only during calibration, for a few seconds at a time. Audio is processed locally and is never stored or transmitted.
