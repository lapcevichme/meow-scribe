"""Прогон пайплайна по папке с wav → JSON в формате экспорта → eval.

  python run.py --data data/ru --name base
  python run.py --data data/ru --name ctc --set asr=giga3_ctc emb=campplus cluster_threshold=0.6
"""
import argparse
import dataclasses
import json
import time
from pathlib import Path

import soundfile as sf

import pipeline as P
from eval import evaluate


def parse_sets(items):
    out = {}
    fields = {f.name: f for f in dataclasses.fields(P.Config)}
    for it in items or []:
        k, v = it.split("=", 1)
        typ = fields[k].type
        if v in ("None", "none"):
            out[k] = None
        elif "int" in str(typ) and "." not in v:
            out[k] = int(v)
        elif "float" in str(typ):
            out[k] = float(v)
        else:
            out[k] = v
    return out


def run_dir(data: Path, out: Path, cfg: P.Config, quiet=False):
    out.mkdir(parents=True, exist_ok=True)
    for wav in sorted(data.glob("*.wav")):
        pcm, sr = sf.read(wav, dtype="float32")
        assert sr == P.SR, f"{wav}: {sr} Гц"
        t0 = time.perf_counter()
        res = P.run(pcm, cfg)
        dt = time.perf_counter() - t0
        (out / f"{wav.stem}.json").write_text(
            json.dumps({"transcript": res.utterances}, ensure_ascii=False, indent=1), encoding="utf-8")
        meta = {"rtf": dt / (len(pcm) / P.SR), "timings": res.timings, "language": res.language}
        (out / f"{wav.stem}.meta.json").write_text(json.dumps(meta))
        if not quiet:
            print(f"  {wav.stem}: rtf {meta['rtf']:.3f} lang {res.language} "
                  + " ".join(f"{k}={v:.1f}s" for k, v in res.timings.items()))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--set", nargs="*")
    a = ap.parse_args()
    cfg = P.Config(**parse_sets(a.set))
    print(cfg)
    out = P.HERE / "out" / f"{Path(a.data).name}_{a.name}"
    run_dir(Path(a.data), out, cfg)
    evaluate(Path(a.data), out)
