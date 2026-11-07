"""Оценка: WER (с пунктуацией и без), DER, ошибка таймстемпов.

  python eval.py --ref data/ru --hyp out/<run>

Для каждого <name>.json в --ref ищет <name>.json в --hyp (формат экспорта приложения).
WER считается по всему диалогу: реплики склеиваются по времени начала.
  wer      — нижний регистр, ё→е, без пунктуации (так обычно нормализуют жюри);
  wer_p    — нижний регистр, ё→е, знаки препинания — отдельные токены (пунктуация входит в WER);
DER — pyannote.metrics, collar 0.25 с (и 0 с), перекрытия учитываются.
ts_err — средняя |Δstart|+|Δend|/2 по репликам эталона, сопоставленным с репликой
гипотезы максимального перекрытия по времени (мс).
"""
import argparse
import json
import re
import sys
from pathlib import Path

import jiwer
from pyannote.core import Annotation, Segment
from pyannote.metrics.diarization import DiarizationErrorRate

PUNCT = r"[.,!?;:…—–\-«»\"()]"


def load(p: Path):
    return sorted(json.loads(p.read_text(encoding="utf-8"))["transcript"], key=lambda u: u["start_ms"])


def norm(text: str, keep_punct: bool) -> str:
    t = text.lower().replace("ё", "е")
    t = re.sub(r"(\w)-(\w)", r"\1\2", t)  # кто-то → ктото, чтобы дефис не ломал счёт
    if keep_punct:
        t = re.sub(r"\.\.\.|…", " … ", t)
        t = re.sub(PUNCT, lambda m: f" {m.group(0)} ", t)
        t = re.sub(r"[\"«»()]", " ", t)
        t = t.replace(" - ", " — ").replace(" – ", " — ")
    else:
        t = re.sub(PUNCT, " ", t)
    return re.sub(r"\s+", " ", t).strip()


def annotation(utts):
    a = Annotation()
    for i, u in enumerate(utts):
        if u["end_ms"] > u["start_ms"]:
            a[Segment(u["start_ms"] / 1000, u["end_ms"] / 1000), i] = u["speaker_id"]
    return a


def ts_error(ref, hyp):
    errs = []
    for r in ref:
        best, ov = None, 0
        for h in hyp:
            o = min(r["end_ms"], h["end_ms"]) - max(r["start_ms"], h["start_ms"])
            if o > ov:
                best, ov = h, o
        if best:
            errs.append((abs(best["start_ms"] - r["start_ms"]) + abs(best["end_ms"] - r["end_ms"])) / 2)
    return sum(errs) / len(errs) if errs else float("nan")


def evaluate(ref_dir: Path, hyp_dir: Path, verbose=True):
    rows = []
    tot = {"wer": [0, 0], "wer_p": [0, 0]}
    der25, der0 = DiarizationErrorRate(collar=0.25), DiarizationErrorRate(collar=0.0)
    for rp in sorted(ref_dir.glob("*.json")):
        hp = hyp_dir / rp.name
        if not hp.exists():
            continue
        ref, hyp = load(rp), load(hp)
        row = {"name": rp.stem}
        for key, kp in (("wer", False), ("wer_p", True)):
            r = norm(" ".join(u["text"] for u in ref), kp)
            h = norm(" ".join(u["text"] for u in hyp), kp)
            o = jiwer.process_words(r, h)
            n = o.hits + o.substitutions + o.deletions
            e = o.substitutions + o.deletions + o.insertions
            row[key] = e / max(n, 1)
            tot[key][0] += e
            tot[key][1] += n
        ra, ha = annotation(ref), annotation(hyp)
        row["der"] = der25(ra, ha)
        row["der0"] = der0(ra, ha)
        row["ts_err"] = ts_error(ref, hyp)
        row["n_spk"] = f"{len({u['speaker_id'] for u in hyp})}/{len({u['speaker_id'] for u in ref})}"
        meta = hyp_dir / f"{rp.stem}.meta.json"
        if meta.exists():
            row["rtf"] = json.loads(meta.read_text())["rtf"]
        rows.append(row)
    if not rows:
        sys.exit(f"нет пар ref/hyp: {ref_dir} vs {hyp_dir}")
    summary = {
        "wer": tot["wer"][0] / tot["wer"][1],
        "wer_p": tot["wer_p"][0] / tot["wer_p"][1],
        "der": abs(der25),
        "der0": abs(der0),
        "ts_err": sum(r["ts_err"] for r in rows) / len(rows),
    }
    if all("rtf" in r for r in rows):
        summary["rtf"] = sum(r["rtf"] for r in rows) / len(rows)
    if verbose:
        for r in rows:
            print(f"{r['name']:<14} wer {r['wer']:.3f}  wer_p {r['wer_p']:.3f}  der {r['der']:.3f}  "
                  f"der0 {r['der0']:.3f}  ts {r['ts_err']:6.0f}ms  spk {r['n_spk']}"
                  + (f"  rtf {r['rtf']:.3f}" if "rtf" in r else ""))
        print("ИТОГО          " + "  ".join(f"{k} {v:.3f}" for k, v in summary.items()))
    return summary, rows


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", required=True)
    ap.add_argument("--hyp", required=True)
    a = ap.parse_args()
    evaluate(Path(a.ref), Path(a.hyp))
