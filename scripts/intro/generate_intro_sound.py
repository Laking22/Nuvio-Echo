#!/usr/bin/env python3
"""Synthesises the playback intro's sound (no samples, no dependencies).

Musical rather than effects-led, in the spirit of the Apple TV intro: a soft low note, a warm
chord, an airy synth swell through the zoom, and the opening mirrored, a little higher, to close.
Timeline matches IntroTimeline in PlaybackIntro.kt (seconds, for the 5 s intro; the 3, 4 and 6 s
intros scale every cue by length / 5, so each part keeps its share):
  0.00  logo lands         - soft, deep bell-like note
  0.35  word slides out    - (silent but for the landing note's tail)
  1.00  "nuvio" holds      - warm low chord, slowly opening up
  3.10  word slides back   - an airy synth pad fades in under it
  3.55  zoom               - the pad swells and brightens as its filter opens
  4.65  zoom completes     - mirrors the opening, just above it in the zoom's key: the warm chord
                             blooms again and fades under the film

The 7–10 s intros open differently (scene.js in the intro lab): the logo's pieces drift together
while the camera circles them, over a swell that builds to a hit as they connect; the joined logo
rests 0.6 s, then the zoom and close play out (timed to the zoom's 1.3 s), sounding as in the others:
  0.00   a low drone and a dark pad, swelling and opening up the whole way (a crescendo), a riser
         and quickening low pulses over the last couple of seconds
  join   the pieces lock: a deep boom, a bell and a bright chord, the swell cut off under it
  +0.06  the zoom's pad, as in the 6 s intro, then the close

Usage: generate_intro_sound.py out.wav [--length S] [--zoom-pitch N] [--end-at S]
Then: ffmpeg -i out.wav -c:a aac -b:a 160k app/src/main/res/raw/intro_<S>s.m4a
For the intro lab: generate_intro_sound.py --stems scripts/intro/build/stems [S ...] (default: every length)
"""
import json
import math
import os
import random
import struct
import sys
import wave

RATE = 44100
# The intro lengths offered (s). Everything below is timed for 5 s and scaled by length / 5,
# except the sounds themselves (a bell's ring, the reverb) and the tail under the film.
LENGTHS = (3, 4, 5, 6, 7, 8, 9, 10)
# From 7 s: the pieces-coming-together opening, then a shorter zoom (as in scene.js).
ORBIT_FROM = 7
ORBIT_ZOOM = 1.74          # the zoom, as long as the 6 s intro's
ORBIT_HOLD = 0.6           # the joined logo rests this long before it
ORBIT = False
JOIN = 0.0
TAIL = 1.8                  # the sound rings on this long under the film
INTRO = 5.0
S = 1.0
LENGTH = INTRO + TAIL
N = int(RATE * LENGTH)
random.seed(11)
# Range of the intro lab's zoom pitch slider, in semitones from A.
STEM_LOW, STEM_HIGH = -12, 2

left = [0.0] * N
right = [0.0] * N


def add(start, samples, gain=1.0, pan=0.0):
    """Adds mono samples at [start] seconds; pan -1 (left) .. 1 (right)."""
    i0 = int(start * RATE)
    gl = gain * math.cos((pan + 1) * math.pi / 4)
    gr = gain * math.sin((pan + 1) * math.pi / 4)
    for i, value in enumerate(samples):
        j = i0 + i
        if j >= N:
            break
        left[j] += value * gl
        right[j] += value * gr


def add_stereo(start, pair, gain=1.0):
    i0 = int(start * RATE)
    for i, (l, r) in enumerate(zip(*pair)):
        j = i0 + i
        if j >= N:
            break
        left[j] += l * gain
        right[j] += r * gain


def tone(freq, t, phase_offset=0.0):
    """Warm tone: fundamental plus a few soft harmonics."""
    p = 2 * math.pi * freq * t + phase_offset
    return math.sin(p) + 0.22 * math.sin(2 * p) + 0.07 * math.sin(3 * p) + 0.03 * math.sin(4 * p)


def bell(freq, duration, decay):
    """Soft mallet strike: quick attack, inharmonic shimmer that fades faster than the body."""
    out = []
    for i in range(int(duration * RATE)):
        t = i / RATE
        attack = 1 - math.exp(-t * 300)
        body = math.sin(2 * math.pi * freq * t) * math.exp(-t * decay)
        sparkle = 0.25 * math.sin(2 * math.pi * freq * 2.76 * t) * math.exp(-t * decay * 3.5)
        sub = 0.6 * math.sin(2 * math.pi * freq / 2 * t) * math.exp(-t * decay * 0.8)
        out.append((body + sparkle + sub) * attack)
    return out


def chord(freqs, duration, envelope, brightness=lambda t: 0.0, detune=0.0025):
    """Sustained chord with detuned pairs (width) and a time-varying upper octave (brightness)."""
    out = []
    for i in range(int(duration * RATE)):
        t = i / RATE
        value = 0.0
        b = brightness(t)
        for n, f in enumerate(freqs):
            weight = 1 / (1 + n * 0.3)
            value += weight * (tone(f * (1 - detune), t) + tone(f * (1 + detune), t, 1.3)) * 0.5
            if b > 0:
                value += weight * b * 0.35 * math.sin(2 * math.pi * f * 2 * t + n)
        out.append(value * envelope(t))
    return out


def poly_blep(phase, dt):
    """Smooths a sawtooth's jump so it doesn't alias (sounds clean rather than buzzy)."""
    if phase < dt:
        x = phase / dt
        return x + x - x * x - 1
    if phase > 1 - dt:
        x = (phase - 1) / dt
        return x * x + x + x + 1
    return 0.0


def synth(notes, duration, pitch, amp, cutoff, voices=5, detune_cents=13, resonance=0.9):
    """Supersaw pad: [voices] detuned saws per note spread across the stereo field, through a
    low-pass filter that can sweep. [pitch], [amp] and [cutoff] are functions of time (s)."""
    count = int(duration * RATE)
    raw_l = [0.0] * count
    raw_r = [0.0] * count
    for n, f in enumerate(notes):
        weight = 1 / (1 + n * 0.25) / voices
        for v in range(voices):
            spread = (v / (voices - 1)) * 2 - 1 if voices > 1 else 0.0
            ratio = 2 ** (spread * detune_cents / 1200)
            pan = spread * 0.85
            gl = weight * math.cos((pan + 1) * math.pi / 4)
            gr = weight * math.sin((pan + 1) * math.pi / 4)
            phase = random.random()
            for i in range(count):
                dt = f * ratio * pitch(i / RATE) / RATE
                phase += dt
                if phase >= 1:
                    phase -= 1
                s = 2 * phase - 1 - poly_blep(phase, dt)
                raw_l[i] += s * gl
                raw_r[i] += s * gr
    k = 1 / resonance
    out = []
    for raw in (raw_l, raw_r):
        ic1 = ic2 = 0.0
        filtered = [0.0] * count
        g = 0.0
        for i in range(count):
            if i % 16 == 0:
                t = i / RATE
                g = math.tan(math.pi * min(cutoff(t), RATE * 0.45) / RATE)
                a1 = 1 / (1 + g * (g + k))
                a2 = g * a1
                a3 = g * a2
            v3 = raw[i] - ic2
            v1 = a1 * ic1 + a2 * v3
            v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2 * v1 - ic1
            ic2 = 2 * v2 - ic2
            filtered[i] = v2 * amp(i / RATE)
        out.append(filtered)
    return out


def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


A1, A2, E3, A3, Cs4, E4, Gs4, B4, Cs5, E5 = 55.0, 110.0, 164.81, 220.0, 277.18, 329.63, 415.30, 493.88, 554.37, 659.25
C4, A4, B3 = 261.63, 440.0, 246.94

def hold_env(t):
    rise = min(1.0, t / (0.9 * S))
    tail = 1.0 if t < HOLD_LEN - 0.6 * S else max(0.0, (HOLD_LEN - t) / (0.6 * S))
    return rise * tail


# 3.10 – 4.68 the synth: an airy bed that fades in as the word returns and swells and brightens
# with the zoom. It holds its pitch: sliding saws up sounded like an engine revving, and high
# plucks above it (removed) like a cartoon laser.
# Tuned by ear with the intro lab's sliders:
ZOOM_SEMITONES = -7         # the zoom's synth, in semitones from A (D)
END_AT_5S = 4.65            # when the close lands in the 5 s intro: as the zoom completes


def set_length(seconds):
    """Times everything for a [seconds]-long intro (5 s as tuned, the rest scaled to it; from 7 s,
    the opening as long as it takes and the zoom as in the 6 s intro)."""
    global INTRO, S, LENGTH, N, HOLD_START, HOLD_LEN, PAD_START, RISE_START, RISE_END, PAD_LEN, END_AT
    global ORBIT, JOIN
    INTRO = float(seconds)
    ORBIT = INTRO >= ORBIT_FROM
    # From 7 s the zoom's cues are timed to its own length (the 5 s intro's zoom is 1.45 s).
    S = ORBIT_ZOOM / 1.45 if ORBIT else INTRO / 5.0
    LENGTH = INTRO + TAIL
    N = int(RATE * LENGTH)
    # Where the zoom part sits: as timed (shift 0), or after the opening.
    JOIN = INTRO - ORBIT_ZOOM - ORBIT_HOLD if ORBIT else 0.0
    shift = (INTRO - ORBIT_ZOOM) - 3.55 * S if ORBIT else 0.0
    HOLD_START = 1.0 * S
    HOLD_LEN = 3.0 * S
    PAD_START = 3.10 * S + shift
    RISE_START = (3.55 - 3.10) * S
    RISE_END = (4.60 - 3.10) * S
    PAD_LEN = (4.70 - 3.10) * S
    END_AT = END_AT_5S * S + shift


set_length(5)


def pad_pitch(t):
    return 1.0


def pad_amp(t):
    if t < RISE_START:
        return 0.35 * smooth(t / RISE_START)
    if t < RISE_END:
        x = (t - RISE_START) / (RISE_END - RISE_START)
        return 0.35 + 0.4 * smooth(x)
    return 0.35 * max(0.0, 1 - (t - RISE_END) / (0.09 * S))


def pad_cutoff(t):
    if t < RISE_START:
        return 500.0
    x = min(1.0, (t - RISE_START) / (RISE_END - RISE_START))
    return 500.0 * (4.4 ** smooth(x * 1.4))     # 500 Hz -> 2.2 kHz: soft, never bright




def svf_band(samples, centre, q=0.7):
    """Band-pass (Chamberlin state-variable); [centre] in Hz as a function of time (s)."""
    low = band = 0.0
    out = []
    for i, x in enumerate(samples):
        f = 2 * math.sin(math.pi * min(centre(i / RATE), RATE / 6) / RATE)
        high = x - low - q * band
        band += f * high
        low += f * band
        out.append(band)
    return out


def boom(start_hz, end_hz, duration, decay):
    """A deep drum-like hit: a sine sliding down in pitch as it fades."""
    out, phase = [], 0.0
    for i in range(int(duration * RATE)):
        t = i / RATE
        hz = end_hz + (start_hz - end_hz) * math.exp(-t * 9)
        phase += 2 * math.pi * hz / RATE
        out.append(math.sin(phase) * math.exp(-t * decay) * (1 - math.exp(-t * 400)))
    return out


def orbit_layer():
    """The 7–10 s opening: a swell from the dark that builds the whole way to the join, where the
    pieces lock with a hit; then the zoom's pad takes over (bed_layer)."""
    j = JOIN
    # A low drone underneath it all, there from the start and growing.
    def drone_env(t):
        return min(1.0, t / 1.5) * (0.55 + 0.45 * (t / j) ** 2) if t < j else math.exp(-(t - j) / 0.5)
    drone = [drone_env(i / RATE) * (tone(A1, i / RATE) + 0.5 * tone(A2, i / RATE, 0.7))
             for i in range(int((j + 2.0) * RATE))]
    add(0.0, drone, 0.16)
    # The crescendo: a dark pad that opens up and grows louder all the way to the join, then stops dead
    # under the hit.
    def swell_amp(t):
        x = min(1.0, t / j)
        cut = 1.0 if t < j - 0.03 else max(0.0, (j - t) / 0.03)
        return (0.03 + 0.55 * x ** 2.4) * min(1.0, t / 0.8) * cut
    def swell_cutoff(t):
        return 240.0 * 16 ** (min(1.0, t / j) ** 1.7)       # 240 Hz -> 3.8 kHz
    random.seed(31)
    add_stereo(0.0, synth([A2, E3, A3, Cs4, E4], j, pad_pitch, swell_amp, swell_cutoff), 0.7)
    # A riser over the last seconds: air rushing in as the pieces close.
    rise = min(3.0, j)
    noise = [random.uniform(-1, 1) for _ in range(int(rise * RATE))]
    riser = svf_band(noise, lambda t: 500 * 12 ** (t / rise))
    riser = [v * (i / len(riser)) ** 3 * (1 if i < len(riser) - 300 else (len(riser) - i) / 300) for i, v in enumerate(riser)]
    add(j - rise, riser, 0.35, pan=-0.15)
    # Low pulses, closer and closer together, louder, into the join.
    for n, before in enumerate((2.1, 1.45, 0.98, 0.64, 0.4, 0.22, 0.1)):
        if j - before > 0.3:
            add(j - before, boom(90, 52, 0.5, 9), 0.18 + 0.06 * n)
    # The join: a deep boom, a bell, a bright chord and a burst of air.
    add(j, boom(70, 36, 3.0, 2.2), 0.95)
    add(j, bell(A2, 3.0, 1.5), 0.45)
    add(j, bell(A3, 2.4, 2.4), 0.22, pan=0.2)
    add(j, bell(E5, 1.4, 3.2), 0.06, pan=-0.3)

    def bloom(t):
        return min(1.0, t / 0.015) * math.exp(-t / 1.0)

    add(j, chord([A2, E3, A3, Cs4, E4, B4], 2.8, bloom, brightness=lambda t: 0.5), 0.2)
    air = svf_band([random.uniform(-1, 1) for _ in range(int(0.8 * RATE))], lambda t: 3000.0)
    add(j, [v * math.exp(-i / RATE * 9) for i, v in enumerate(air)], 0.4, pan=0.1)


def base_layer():
    if ORBIT:
        orbit_layer()
        return
    # 0.00 landing: soft deep note.
    add(0.0, bell(A2, 2.4, 2.2), 0.3)
    # 1.00 – 4.00 warm low chord under "nuvio", opening slowly, easing off as the pad takes over.
    add(HOLD_START, chord(list(HOLD_CHORD), HOLD_LEN, hold_env, brightness=lambda t: 0.25 * min(1.0, t / HOLD_LEN)), 0.11)


ZOOM_CHORD = (A2, E3, A3, B3, Cs4, E4)
HOLD_CHORD = (A2, E3, A3, Cs4)


def bed_layer(semitones):
    """The zoom's synth bed, [semitones] from A."""
    random.seed(23)                      # the same voices at every pitch
    key = 2 ** (semitones / 12)
    add_stereo(PAD_START, synth([f * key for f in ZOOM_CHORD], PAD_LEN, pad_pitch, pad_amp, pad_cutoff), 0.8)


def end_note_layer(zoom_semitones, at):
    """The close mirrors the opening, landing just above it in the zoom's own key so the swell
    resolves: the warm chord from under "nuvio" blooms as the zoom completes (quicker than at the
    start) and fades under the film. Up from the opening A2 by the zoom's key, within an octave
    (a fourth, D, at -7). No bell: its low thud at the close was one too many."""
    above = zoom_semitones % 12 or 12
    key = 2 ** (above / 12)

    def bloom(t):
        rise = 0.35 * S
        return min(1.0, t / rise) * math.exp(-max(0.0, t - rise) / 1.4)

    add(at, chord([f * key for f in HOLD_CHORD], LENGTH - at, bloom, brightness=lambda t: 0.25), 0.11)


def freeverb(dry, spread=0, room=0.88, damp=0.3):
    """Schroeder/Freeverb room: long, soft tail for the airy feel. [spread] widens the stereo."""
    combs = [[0.0] * (d + spread) for d in (1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)]
    comb_pos = [0] * len(combs)
    comb_low = [0.0] * len(combs)
    passes = [[0.0] * (d + spread) for d in (556, 441, 341, 225)]
    pass_pos = [0] * len(passes)
    wet = [0.0] * len(dry)
    for i, x in enumerate(dry):
        inp = x * 0.015
        acc = 0.0
        for c, buf in enumerate(combs):
            j = comb_pos[c]
            y = buf[j]
            comb_low[c] = y * (1 - damp) + comb_low[c] * damp
            buf[j] = inp + comb_low[c] * room
            comb_pos[c] = j + 1 if j + 1 < len(buf) else 0
            acc += y
        for a, buf in enumerate(passes):
            j = pass_pos[a]
            b = buf[j]
            buf[j] = acc + b * 0.5
            pass_pos[a] = j + 1 if j + 1 < len(buf) else 0
            acc = b - acc
        wet[i] = acc
    return wet


def render(layer):
    """[layer] mixed through the reverb, with the closing fade; not yet scaled."""
    global left, right
    left, right = [0.0] * N, [0.0] * N
    layer()
    # The reverb is silent until the first sound, so start it there.
    first = next((i for i in range(N) if left[i] or right[i]), N)
    wet_l = [0.0] * first + freeverb(left[first:])
    wet_r = [0.0] * first + freeverb(right[first:], spread=23)
    mix_l = [left[i] * 0.75 + wet_l[i] * 1.6 for i in range(N)]
    mix_r = [right[i] * 0.75 + wet_r[i] * 1.6 for i in range(N)]
    fade = int(0.8 * RATE)
    for i in range(fade):
        g = 1 - i / fade
        mix_l[N - fade + i] *= g
        mix_r[N - fade + i] *= g
    return mix_l, mix_r


def peak_of(*mixes, start=0):
    return max(abs(sum(m[c][i] for m in mixes)) for c in (0, 1) for i in range(start, N))


def write(path, mix, scale):
    with wave.open(path, "wb") as f:
        f.setnchannels(2)
        f.setsampwidth(2)
        f.setframerate(RATE)
        frames = bytearray()
        for l, r in zip(*mix):
            frames += struct.pack("<hh", int(max(-1, min(1, l * scale)) * 32767), int(max(-1, min(1, r * scale)) * 32767))
        f.writeframes(bytes(frames))


HEADROOM = 0.5     # stems are written this far below full scale so the end note fits on top


def read_wav(path):
    """A stem written by write(), back as float lists (at the scale it was written)."""
    with wave.open(path) as f:
        data = f.readframes(f.getnframes())
    values = struct.unpack("<%dh" % (len(data) // 2), data)
    return [v / 32767 for v in values[0::2]], [v / 32767 for v in values[1::2]]


def end_note_stem(stems_root, length, pitch, at):
    """For the intro lab: the closing note of the [length] s intro as WAV bytes at its stems'
    scale, plus the gain that normalises base + bed + note exactly as the final sound is."""
    set_length(length)
    stems_dir = os.path.join(stems_root, str(length))
    with open(os.path.join(stems_dir, "manifest.json")) as f:
        scale = json.load(f)["scale"]
    end = render(lambda: end_note_layer(pitch, at))
    end = tuple([v * scale for v in ch] for ch in end)
    base = read_wav(os.path.join(stems_dir, "base.wav"))
    bed = read_wav(os.path.join(stems_dir, f"bed_{pitch}.wav"))
    gain = 0.89 / peak_of(base, bed, end)
    path = os.path.join(stems_dir, "end_note.tmp.wav")
    write(path, end, 1.0)
    with open(path, "rb") as f:
        body = f.read()
    os.remove(path)
    return body, gain


if __name__ == "__main__":
    args = sys.argv[1:]

    def option(name, default, kind=int):
        return kind(args[args.index(name) + 1]) if name in args else default

    if args and args[0] == "--stems":
        # For the intro lab, per length (a folder each): the intro without its zoom synth (base)
        # and the bed at each pitch, all at one scale (in the manifest). The lab server renders
        # the closing note on demand (end_note_stem) since its slider changes its timing.
        for length in [int(a) for a in args[2:]] or LENGTHS:
            set_length(length)
            out_dir = os.path.join(args[1], str(length))
            os.makedirs(out_dir, exist_ok=True)
            for name in os.listdir(out_dir):
                os.remove(os.path.join(out_dir, name))
            random.seed(11)
            base = render(base_layer)
            beds = {p: render(lambda: bed_layer(p)) for p in range(STEM_LOW, STEM_HIGH + 1)}
            scale = HEADROOM * min(0.89 / peak_of(base, bed) for bed in beds.values())
            write(os.path.join(out_dir, "base.wav"), base, scale)
            for p, bed in beds.items():
                write(os.path.join(out_dir, f"bed_{p}.wav"), bed, scale)
            manifest = {"scale": scale, "default": {"zoomPitch": ZOOM_SEMITONES, "endAt": round(END_AT, 3)}}
            with open(os.path.join(out_dir, "manifest.json"), "w") as f:
                json.dump(manifest, f, indent=1)
    else:
        set_length(option("--length", 5, float))
        semitones = option("--zoom-pitch", ZOOM_SEMITONES)
        at = option("--end-at", END_AT, float)
        random.seed(11)
        layers = [
            render(base_layer),
            render(lambda: bed_layer(semitones)),
            render(lambda: end_note_layer(semitones, at)),
        ]
        both = tuple([sum(m[c][i] for m in layers) for i in range(N)] for c in (0, 1))
        write(args[0], both, 0.89 / peak_of(*layers))
