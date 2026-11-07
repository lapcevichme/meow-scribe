"""Перебор параметров диаризации при фиксированном ASR (слова кэшируются).

  python sweep_diar.py --data data/ru --emb eres2net campplus --thr 0.5 0.6 0.7 0.8
"""
import argparse
import dataclasses
import itertools
import json
import pickle
import time
from pathlib import Path

import soundfile as sf

import pipeline as P
from eval import evaluate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--asr", default="giga3_rnnt")
    ap.add_argument("--emb", nargs="+", default=["eres2net"])
    ap.add_argument("--thr", nargs="+", type=float, default=[0.5])
    ap.add_argument("--min-on", nargs="+", type=float, default=[0.3])
    ap.add_argument("--min-off", nargs="+", type=float, default=[0.5])
    ap.add_argument("--oracle", action="store_true", help="подать истинное число спикеров")
    a = ap.parse_args()

    files = []
    for d in a.data:
        files += sorted(Path(d).glob("*.wav"))
    base = P.Config(asr=a.asr)
    cache = P.HERE / ".cache/words"
    cache.mkdir(parents=True, exist_ok=True)
    audio, words = {}, {}
    for wav in files:
        pcm, _ = sf.read(wav, dtype="float32")
        audio[wav] = pcm
        cp = cache / f"{wav.stem}.{a.asr}.pkl"
        if not cp.exists():
            chunks = P.vad_chunks(pcm, base)
            lang = "en" if wav.stem.startswith("en") else "ru"
            rec = P.get_rec(a.asr if lang == "ru" else base.asr_en, base.threads)
            cp.write_bytes(pickle.dumps(P.run_asr(rec, pcm, chunks)))
        words[wav] = pickle.loads(cp.read_bytes())

    results = []
    for emb, thr, mon, moff in itertools.product(a.emb, a.thr, a.min_on, a.min_off):
        cfg = dataclasses.replace(base, emb=emb, cluster_threshold=thr, min_on=mon, min_off=moff)
        tag = f"{emb}_t{thr}_on{mon}_off{moff}{'_oracle' if a.oracle else ''}"
        t_diar, dur = 0.0, 0.0
        outs = {}
        for wav in files:
            if a.oracle:
                ref = json.loads(wav.with_suffix(".json").read_text(encoding="utf-8"))["transcript"]
                cfg = dataclasses.replace(cfg, num_speakers=len({u["speaker_id"] for u in ref}))
            t0 = time.perf_counter()
            segs = P.diarize(audio[wav], cfg)
            t_diar += time.perf_counter() - t0
            dur += len(audio[wav]) / P.SR
            ws = [dataclasses.replace(w) for w in words[wav]]
            P.assign_speakers(ws, segs)
            out = P.HERE / "out" / "sweep" / tag / wav.parent.name
            out.mkdir(parents=True, exist_ok=True)
            (out / f"{wav.stem}.json").write_text(json.dumps(
                {"transcript": P.build_utterances(ws, base.split_gap)}, ensure_ascii=False), encoding="utf-8")
            outs[wav.parent] = out
        summ = {}
        for ref_dir, out in outs.items():
            s, _ = evaluate(ref_dir, out, verbose=False)
            summ[ref_dir.name] = s
        line = f"{tag:<45} diar_rtf {t_diar / dur:.3f}  " + "  ".join(
            f"{k}: der {v['der']:.3f} der0 {v['der0']:.3f} wer_p {v['wer_p']:.3f}" for k, v in summ.items())
        print(line, flush=True)
        results.append(line)


if __name__ == "__main__":
    main()
