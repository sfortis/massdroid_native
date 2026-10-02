"""Generates sync-probe-chirps.flac, the test clip the sync probe plays as an announcement.

The clip is 30 s of mono chirps (300 Hz to 6 kHz, up or down, 40 to 120 ms long) at random
intervals, so it does not resemble itself at any lag: music with loops matches itself a beat
and a bar away, which made the probe find false peaks. The seed is fixed, so the output is
the same file every time.

Usage: python3 make_chirps.py out.wav && ffmpeg -i out.wav -c:a flac sync-probe-chirps.flac
"""
import numpy as np, wave, sys
rate = 48000
seconds = 30
rng = np.random.default_rng(20261002)
out = np.zeros(rate * seconds)
t = 0.25
count = 0
while t < seconds - 0.3:
    dur = rng.uniform(0.04, 0.12)
    f0, f1 = 300.0, 6000.0
    if rng.random() < 0.5:
        f0, f1 = f1, f0
    n = int(dur * rate)
    tt = np.arange(n) / rate
    k = np.log(f1 / f0) / dur
    phase = 2 * np.pi * f0 * (np.exp(k * tt) - 1) / k
    burst = np.sin(phase) * np.hanning(n)
    start = int(t * rate)
    out[start:start + n] += burst * rng.uniform(0.6, 1.0)
    count += 1
    t += dur + rng.uniform(0.12, 0.45)
out *= 0.5 / np.max(np.abs(out))
pcm = (out * 32767).astype('<i2')
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm.tobytes())
# Self-similarity check: autocorrelation sidelobes relative to the zero-lag peak.
x = out
n = 1 << int(np.ceil(np.log2(2 * len(x))))
X = np.fft.rfft(x, n)
ac = np.fft.irfft(X * np.conj(X), n)[: rate * 2]
ac /= ac[0]
side = np.max(np.abs(ac[int(0.002 * rate):]))
print(f"chirps={count} max sidelobe within 2 s = {side:.3f}")
