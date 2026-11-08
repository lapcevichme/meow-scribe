# Артефакты тестирования

`results.csv` — сводка по наборам стенда: WER, WER с пунктуацией, DER (по репликам, collar 0.25 с), cpWER, RTF.

Методика, описание наборов и разбор, что дал каждый шаг пайплайна, — в [`docs/TEST_RESULTS.md`](../../docs/TEST_RESULTS.md). Все замеры и сравнения моделей — в [`bench/README.md`](../../bench/README.md).

Тестовые аудио в репозиторий не кладём: русские диалоги собираются из Common Voice скриптом `bench/make_dialogs.py`, AMI — скриптом `bench/make_ami.py`.
