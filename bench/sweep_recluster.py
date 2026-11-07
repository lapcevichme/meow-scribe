"""Перебор второго прохода диаризации (recluster_sim, min_spk_sec) поверх кэша слов ASR и сегментов sherpa.

  python sweep_recluster.py --data data/ru data/ru_multi data/en_ami --sim 0 0.5 0.6 --min-spk 0 3 6
"""
import argparse
import dataclasses
import itertools
import json
import pickle
from pathlib import Path

import soundfile as sf

import pipeline as P
from eval import evaluate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--sim", nargs="+", type=float, default=[0.0])
    ap.add_argument("--min-spk", nargs="+", type=float, default=[0.0])
    a = ap.parse_args()

    base = P.Config()
    cache = P.HERE / ".cache/words"
    cache.mkdir(parents=True, exist_ok=True)
    files = [w for d in a.data for w in sorted(Path(d).glob("*.wav"))]
    audio, words, segs = {}, {}, {}
    for wav in files:
        pcm, _ = sf.read(wav, dtype="float32")
        audio[wav] = pcm
        lang = "en" if wav.stem.startswith("en") else "ru"
        asr = base.asr if lang == "ru" else base.asr_en
        cw = cache / f"{wav.stem}.{asr}.v2.pkl"  # v2: VAD с нарезкой длинных кусков
        if not cw.exists():
            cw.write_bytes(pickle.dumps(P.run_asr(P.get_rec(asr, base.threads), pcm, P.vad_chunks(pcm, base))))
        words[wav] = pickle.loads(cw.read_bytes())
        cs = cache / f"{wav.stem}.segs.pkl"
        if not cs.exists():
            cs.write_bytes(pickle.dumps(P.diarize(pcm, base)))
        segs[wav] = pickle.loads(cs.read_bytes())

    for sim, mn in itertools.product(a.sim, a.min_spk):
        cfg = dataclasses.replace(base, recluster_sim=sim, min_spk_sec=mn)
        tag = f"sim{sim}_min{mn}"
        outs = {}
        for wav in files:
            sg = P.recluster(audio[wav], segs[wav], cfg) if (sim > 0 or mn > 0) else segs[wav]
            ws = [dataclasses.replace(w) for w in words[wav]]
            P.assign_speakers(ws, sg)
            out = P.HERE / "out/recluster" / tag / wav.parent.name
            out.mkdir(parents=True, exist_ok=True)
            (out / f"{wav.stem}.json").write_text(json.dumps(
                {"transcript": P.build_utterances(ws, base.split_gap, base.sent_gap)}, ensure_ascii=False),
                encoding="utf-8")
            outs[wav.parent] = out
        parts = []
        for ref_dir, out in outs.items():
            s, rows = evaluate(ref_dir, out, verbose=False)
            spk = " ".join(r["n_spk"] for r in rows)
            parts.append(f"{ref_dir.name}: der {s['der']:.3f} [{spk}]")
        print(f"{tag:<16} " + " | ".join(parts), flush=True)


if __name__ == "__main__":
    main()
