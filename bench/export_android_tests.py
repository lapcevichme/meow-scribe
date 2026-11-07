"""Кладёт многоголосые записи с эталонами в androidTest/assets/multi/ для MultiSpeakerTest.

  python export_android_tests.py            # набор по умолчанию (~12 мин аудио)
  python export_android_tests.py --all      # все файлы из data/ru_multi* и data/en_ami*
"""
import argparse
import shutil
from pathlib import Path

HERE = Path(__file__).parent
DST = HERE.parent / "android/app/src/androidTest/assets/multi"
DEFAULT = [
    "ru_multi/ru_multi_2spk_00",
    "ru_multi/ru_multi_3spk_01",
    "ru_multi/ru_multi_4spk_02",
    "ru_multi_far/ru_multi_4spk_02_far",
    "ru_multi_cheap/ru_multi_4spk_02_cheap",
    "ru_multi_babble/ru_multi_3spk_01_babble",
    "ru_multi_quiet/ru_multi_3spk_01_quiet",
    "en_ami/en_ami_ES2004a",
    "en_ami_far/en_ami_far_ES2004a",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true")
    a = ap.parse_args()
    data = HERE / "data"
    if a.all:
        items = [w.with_suffix("") for d in sorted(data.glob("ru_multi*")) + sorted(data.glob("en_ami*"))
                 for w in sorted(d.glob("*.wav"))]
    else:
        items = [data / s for s in DEFAULT]
    if DST.exists():
        shutil.rmtree(DST)
    DST.mkdir(parents=True)
    for base in items:
        for ext in (".wav", ".json"):
            shutil.copy(base.with_suffix(ext), DST / (base.name + ext))
    print(f"{len(items)} файлов → {DST}")


if __name__ == "__main__":
    main()
