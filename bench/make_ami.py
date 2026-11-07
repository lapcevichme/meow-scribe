"""Живые совещания AMI (англ., 3–4 спикера) с ручной разметкой слов.

Для каждого совещания режется окно в несколько минут из двух записей:
  data/en_ami      — Mix-Headset: сумма гарнитур, близкий микрофон;
  data/en_ami_far  — Array1-01: один микрофон массива на столе (дальнее поле, эхо комнаты).
Эталон общий: слова из ami_public_manual_1.6.2 (words/*.xml) → реплики по спикеру,
новая реплика при паузе > 0.6 с. Перекрытия речи сохраняются.

  python make_ami.py                     # 4 совещания по 4 минуты
  python make_ami.py --len 300 --start 600
"""
import argparse
import json
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16000
HERE = Path(__file__).parent
CACHE = HERE / ".cache/ami"
MIRROR = "https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus"
ANN_URL = "https://groups.inf.ed.ac.uk/ami/AMICorpusAnnotations/ami_public_manual_1.6.2.zip"
# совещания из тестовой части AMI: разные комнаты и составы
MEETINGS = ["ES2004a", "IS1009a", "TS3003a", "EN2002a"]
SOURCES = {"en_ami": "Mix-Headset", "en_ami_far": "Array1-01"}
NITE = "{http://nite.sourceforge.net/}"


def fetch(url: str, dst: Path) -> Path:
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        print(f"  get {url}")
        tmp = dst.with_suffix(dst.suffix + ".part")
        urllib.request.urlretrieve(url, tmp)
        tmp.rename(dst)
    return dst


def annotations() -> Path:
    ann = CACHE / "ann"
    if not (ann / "words").exists():
        z = fetch(ANN_URL, CACHE / "ami_public_manual_1.6.2.zip")
        zipfile.ZipFile(z).extractall(ann)
    return ann / "words"


def load_audio(path: Path) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", str(path), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        capture_output=True, check=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def words(words_dir: Path, meeting: str):
    """[(speaker, start, end, token, is_punct)] по всем спикерам совещания."""
    out = []
    for f in sorted(words_dir.glob(f"{meeting}.*.words.xml")):
        spk = f.name.split(".")[1]
        for el in ET.parse(f).getroot():
            if el.tag != "w" or not el.text:
                continue
            s, e = el.get("starttime"), el.get("endtime")
            if s is None or e is None:
                continue
            out.append((spk, float(s), float(e), el.text.strip(), el.get("punc") == "true"))
    return out


def reference(ws, t0: float, t1: float, split_gap: float):
    by_spk = {}
    for spk, s, e, tok, punc in ws:
        mid = (s + e) / 2
        if t0 <= mid < t1:
            by_spk.setdefault(spk, []).append((s, e, tok, punc))
    utts = []
    for spk, items in by_spk.items():
        items.sort(key=lambda x: x[0])
        cur = None
        for s, e, tok, punc in items:
            if punc:
                if cur:
                    cur["text"] += tok
                continue
            if cur is None or s - cur["end"] > split_gap:
                if cur:
                    utts.append(cur)
                cur = {"spk": spk, "start": s, "end": e, "text": tok}
            else:
                cur["text"] += " " + tok
                cur["end"] = max(cur["end"], e)
        if cur:
            utts.append(cur)
    utts.sort(key=lambda u: u["start"])
    names = {}
    return [{
        "speaker_id": names.setdefault(u["spk"], f"speaker_{len(names) + 1}"),
        "start_ms": round((max(u["start"], t0) - t0) * 1000),
        "end_ms": round((min(u["end"], t1) - t0) * 1000),
        "text": u["text"],
    } for u in utts]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--meetings", nargs="+", default=MEETINGS)
    ap.add_argument("--start", type=float, default=300, help="начало окна, с")
    ap.add_argument("--len", type=float, default=240, help="длина окна, с")
    ap.add_argument("--split-gap", type=float, default=0.6)
    a = ap.parse_args()

    wdir = annotations()
    for m in a.meetings:
        ws = words(wdir, m)
        t0, t1 = a.start, a.start + a.len
        ref = reference(ws, t0, t1, a.split_gap)
        n_spk = len({u["speaker_id"] for u in ref})
        for folder, src in SOURCES.items():
            wav = fetch(f"{MIRROR}/{m}/audio/{m}.{src}.wav", CACHE / "audio" / f"{m}.{src}.wav")
            pcm = load_audio(wav)[int(t0 * SR):int(t1 * SR)]
            pcm *= 0.5 / (np.abs(pcm).max() + 1e-6)  # массив пишет очень тихо
            out = HERE / "data" / folder
            out.mkdir(parents=True, exist_ok=True)
            name = f"{folder}_{m}"
            sf.write(out / f"{name}.wav", pcm, SR, subtype="PCM_16")
            (out / f"{name}.json").write_text(
                json.dumps({"transcript": ref}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{m}: {a.len:.0f}s, {n_spk} spk, {len(ref)} utts, "
              f"{sum(len(u['text'].split()) for u in ref)} words")


if __name__ == "__main__":
    main()
