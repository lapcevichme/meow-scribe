"""Собирает синтетические диалоги из Common Voice с точным эталоном.

Каждая реплика — один клип CV одного говорящего (client_id), обрезанный по VAD.
Между репликами случайная пауза или наложение (перебивание). Эталон пишется
в формате экспорта приложения + поле lang.

  python make_dialogs.py --lang ru --n 8 --out data/ru
  python make_dialogs.py --lang ru --n 9 --spk 2 3 4 --turns 24 --no-phone --prefix ru_multi --out data/ru_multi
"""
import argparse
import csv
import json
import random
import subprocess
from collections import defaultdict
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

SR = 16000
HERE = Path(__file__).parent
VAD_MODEL = HERE / ".cache/models/silero_vad.onnx"


def load_mp3(path: Path) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", str(path), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        capture_output=True, check=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def make_vad():
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = str(VAD_MODEL)
    cfg.silero_vad.min_silence_duration = 0.3
    cfg.silero_vad.min_speech_duration = 0.1
    cfg.sample_rate = SR
    return cfg


def trim(x: np.ndarray, vad_cfg) -> np.ndarray | None:
    """Оставляет звук от начала первого до конца последнего речевого сегмента (+60 мс)."""
    vad = sherpa_onnx.VoiceActivityDetector(vad_cfg, buffer_size_in_seconds=60)
    w = vad_cfg.silero_vad.window_size
    segs = []
    for i in range(0, len(x) - w + 1, w):
        vad.accept_waveform(x[i:i + w])
        while not vad.empty():
            segs.append((vad.front.start, vad.front.start + len(vad.front.samples)))
            vad.pop()
    vad.flush()
    while not vad.empty():
        segs.append((vad.front.start, vad.front.start + len(vad.front.samples)))
        vad.pop()
    if not segs:
        return None
    pad = int(0.06 * SR)
    return x[max(0, segs[0][0] - pad):min(len(x), segs[-1][1] + pad)]


def phone(x: np.ndarray) -> np.ndarray:
    """«Телефонный» канал: 8 кГц, полоса 300–3400 Гц, лёгкий шум."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
         "-af", "highpass=f=300,lowpass=f=3400,aresample=8000,aresample=16000",
         "-f", "f32le", "-"],
        input=x.astype(np.float32).tobytes(), capture_output=True, check=True,
    ).stdout
    y = np.frombuffer(raw, dtype=np.float32).copy()[: len(x)]
    return y + np.random.default_rng(0).normal(0, 0.004, len(y)).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="ru")
    ap.add_argument("--n", type=int, default=8)
    ap.add_argument("--turns", type=int, default=14)
    ap.add_argument("--out", default=None)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--min-clips", type=int, default=4)
    ap.add_argument("--spk", type=int, nargs="+", default=None,
                    help="число спикеров по кругу (по умолчанию случайно 2/2/3)")
    ap.add_argument("--prefix", default=None, help="префикс имён файлов (по умолчанию язык)")
    ap.add_argument("--no-phone", action="store_true", help="не делать последний файл «телефонным»")
    args = ap.parse_args()
    rnd = random.Random(args.seed)

    cv = HERE / ".cache/cv" / args.lang
    out = Path(args.out or HERE / "data" / args.lang)
    out.mkdir(parents=True, exist_ok=True)

    by_spk = defaultdict(list)
    with open(cv / "test.tsv", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            p = cv / f"{args.lang}_test_0" / row["path"]
            if p.exists() and row["sentence"].strip():
                by_spk[row["client_id"]].append((p, row["sentence"].strip()))
    speakers = [s for s, v in by_spk.items() if len(v) >= args.min_clips]
    print(f"{len(speakers)} speakers with >={args.min_clips} clips")
    vad_cfg = make_vad()

    for d in range(args.n):
        n_spk = args.spk[d % len(args.spk)] if args.spk else rnd.choice([2, 2, 3])
        spk = rnd.sample(speakers, n_spk)
        pools = {s: rnd.sample(by_spk[s], len(by_spk[s])) for s in spk}
        audio = np.zeros(int(0.8 * SR), dtype=np.float32)
        ref, prev, cursor = [], None, len(audio)
        for t in range(args.turns):
            choices = [s for s in spk if s != prev and pools[s]] or [s for s in spk if pools[s]]
            if not choices:
                break
            s = rnd.choice(choices)
            path, text = pools[s].pop()
            clip = trim(load_mp3(path), vad_cfg)
            if clip is None:
                continue
            clip *= 0.6 / (np.abs(clip).max() + 1e-6) * rnd.uniform(0.6, 1.0)
            r = rnd.random()
            if t > 0 and r < 0.15:   # перебивание: наложение до 0.7 с
                gap = -rnd.uniform(0.2, 0.7)
            elif r < 0.5:            # быстрая смена реплик
                gap = rnd.uniform(0.05, 0.35)
            else:
                gap = rnd.uniform(0.4, 1.5)
            start = max(0, cursor + int(gap * SR))
            end = start + len(clip)
            if end > len(audio):
                audio = np.concatenate([audio, np.zeros(end - len(audio), dtype=np.float32)])
            audio[start:end] += clip
            cursor = max(cursor, end)
            ref.append({"speaker_id": s, "start_ms": round(start * 1000 / SR),
                        "end_ms": round(end * 1000 / SR), "text": text})
            prev = s
        audio = np.concatenate([audio, np.zeros(int(0.8 * SR), dtype=np.float32)])
        audio += np.random.default_rng(d).normal(0, 0.002, len(audio)).astype(np.float32)
        is_phone = d == args.n - 1 and not args.no_phone
        if is_phone:
            audio = phone(audio)
        # переименовываем спикеров по порядку появления
        names = {}
        for u in ref:
            u["speaker_id"] = names.setdefault(u["speaker_id"], f"speaker_{len(names) + 1}")
        tag = f"_{n_spk}spk" if args.spk else ""
        name = f"{args.prefix or args.lang}{tag}_{d:02d}{'_phone' if is_phone else ''}"
        sf.write(out / f"{name}.wav", np.clip(audio, -1, 1), SR, subtype="PCM_16")
        (out / f"{name}.json").write_text(
            json.dumps({"transcript": ref}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{name}: {len(audio) / SR:.0f}s, {n_spk} spk, {len(ref)} turns")


if __name__ == "__main__":
    main()
