"""Портит звук готового набора, эталон копируется как есть (тайминги не меняются).

Варианты:
  far    — телефон лежит на столе в комнате: реверберация RT60 0.5–0.9 с, затухание, шум комнаты (SNR ~15 дБ);
  cheap  — дешёвый микрофон: перегруз и клиппинг, полоса 200–4000 Гц, фон 50 Гц, MP3 24 кбит/с;
  babble — фоновый гул голосов (кафе/офис) из чужих клипов Common Voice, SNR ~10 дБ;
  quiet  — тихая запись издалека: −30 дБ, шум АЦП, потом цифровое усиление (как AGC диктофона).

  python degrade.py --src data/ru_multi --kinds far cheap babble quiet
  → data/ru_multi_far, data/ru_multi_cheap, …
"""
import argparse
import csv
import shutil
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16000
HERE = Path(__file__).parent


def ffmpeg(x: np.ndarray, *args: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-", *args,
         "-f", "f32le", "-ar", str(SR), "-ac", "1", "-"],
        input=x.astype(np.float32).tobytes(), capture_output=True, check=True,
    ).stdout
    y = np.frombuffer(raw, dtype=np.float32).copy()
    return np.pad(y, (0, max(0, len(x) - len(y))))[: len(x)]


def rms(x):
    return float(np.sqrt(np.mean(x ** 2)) + 1e-9)


def add_noise(x, noise, snr_db):
    return x + noise * (rms(x) / rms(noise)) / (10 ** (snr_db / 20))


def pink(n, rng):
    """Розовый шум через спектр 1/f."""
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = (rng.normal(size=len(f)) + 1j * rng.normal(size=len(f))) / np.sqrt(np.maximum(f, 20))
    y = np.fft.irfft(spec, n)
    return (y / rms(y)).astype(np.float32)


def rir(rt60, rng):
    """Синтетическая импульсная характеристика комнаты: прямой звук + ранние отражения + экспоненциальный хвост."""
    n = int(rt60 * 1.2 * SR)
    t = np.arange(n) / SR
    h = rng.normal(size=n) * np.exp(-6.9 * t / rt60)  # −60 дБ за rt60
    h[: int(0.003 * SR)] = 0
    for _ in range(8):  # ранние отражения 3–30 мс
        h[int(rng.uniform(0.003, 0.03) * SR)] += rng.uniform(0.2, 0.6) * rng.choice([-1, 1])
    h = np.convolve(h, np.ones(4) / 4, mode="same")  # стены гасят верха
    h[0] = 1.0 / rng.uniform(1.5, 3.0)  # прямой звук слабее хвоста: говорящий в 1.5–3 м
    return (h / np.abs(h).max()).astype(np.float32)


def far(x, rng):
    y = np.convolve(x, rir(rng.uniform(0.5, 0.9), rng))[: len(x)]
    y = add_noise(y, ffmpeg(pink(len(x), rng), "-af", "lowpass=f=1500"), rng.uniform(13, 17))
    return y


def cheap(x, rng):
    y = ffmpeg(x, "-af", "highpass=f=200,lowpass=f=4000")
    y = y / (np.abs(y).max() + 1e-6) * rng.uniform(2.0, 3.0)  # перегруз ×2–3
    y = np.tanh(1.5 * y) / np.tanh(1.5)
    y = np.clip(y, -0.8, 0.8)
    t = np.arange(len(y)) / SR
    hum = 0.01 * (np.sin(2 * np.pi * 50 * t) + 0.5 * np.sin(2 * np.pi * 150 * t))
    y = y + hum + rng.normal(0, 0.006, len(y))
    return mp3(y.astype(np.float32), "24k")


def mp3(x, bitrate):
    enc = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
         "-c:a", "libmp3lame", "-b:a", bitrate, "-f", "mp3", "-"],
        input=x.tobytes(), capture_output=True, check=True,
    ).stdout
    dec = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-f", "mp3", "-i", "-", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-"],
        input=enc, capture_output=True, check=True,
    ).stdout
    y = np.frombuffer(dec, dtype=np.float32).copy()
    return np.pad(y, (0, max(0, len(x) - len(y))))[: len(x)]


_babble_pool = None


def babble_pool(rng, lang="en", n=60):
    """Клипы CV чужого языка: слова фона не совпадают со словами эталона."""
    global _babble_pool
    if _babble_pool is None:
        cv = HERE / ".cache/cv" / lang
        with open(cv / "test.tsv", encoding="utf-8") as f:
            rows = [r["path"] for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE)]
        paths = [cv / f"{lang}_test_0" / p for p in rows]
        paths = [p for p in paths if p.exists()]
        pick = rng.choice(len(paths), size=min(n, len(paths)), replace=False)
        _babble_pool = [ffmpeg_load(paths[i]) for i in pick]
    return _babble_pool


def ffmpeg_load(path):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", str(path), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    y = np.frombuffer(raw, dtype=np.float32).copy()
    return y / rms(y)


def babble(x, rng):
    pool = babble_pool(rng)
    bg = np.zeros(len(x), dtype=np.float32)
    for _ in range(6):  # 6 «дорожек» фоновых голосов
        pos = 0
        while pos < len(x):
            c = pool[rng.integers(len(pool))]
            seg = c[: len(x) - pos]
            bg[pos:pos + len(seg)] += seg
            pos += len(seg) + int(rng.uniform(0, 0.5) * SR)
    bg = ffmpeg(bg, "-af", "lowpass=f=3000")  # далёкие голоса глуше
    return add_noise(x, bg, rng.uniform(9, 11))


def quiet(x, rng):
    y = x * 10 ** (-30 / 20)
    y = y + rng.normal(0, 3e-4, len(y))  # шум АЦП/предусилителя
    y = np.round(y * 32767) / 32767  # 16 бит на тихом сигнале
    return y / (np.abs(y).max() + 1e-6) * 0.7


KINDS = {"far": far, "cheap": cheap, "babble": babble, "quiet": quiet}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--kinds", nargs="+", default=list(KINDS), choices=list(KINDS))
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    src = Path(a.src)
    for kind in a.kinds:
        dst = src.parent / f"{src.name}_{kind}"
        dst.mkdir(parents=True, exist_ok=True)
        for i, wav in enumerate(sorted(src.glob("*.wav"))):
            rng = np.random.default_rng(a.seed * 1000 + i)
            x, sr = sf.read(wav, dtype="float32")
            assert sr == SR
            y = KINDS[kind](x, rng)
            y = y / max(1.0, float(np.abs(y).max()) / 0.99)
            sf.write(dst / f"{wav.stem}_{kind}.wav", y, SR, subtype="PCM_16")
            shutil.copy(wav.with_suffix(".json"), dst / f"{wav.stem}_{kind}.json")
        print(f"{dst}: {i + 1} files")


if __name__ == "__main__":
    main()
