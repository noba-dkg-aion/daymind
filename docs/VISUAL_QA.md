# Visual QA Runbook

DayMind ships a visual polish pass on every sprint. Use this checklist before tagging a release or sharing builds with stakeholders.

## 1. Capture reference screenshots

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt kivy==2.2.1 kivymd==1.1.1
python scripts/ui_snapshot.py --output artifacts/ui/latest
deactivate
```

The script navigates through the Record/Summary/Settings screens and saves fresh PNGs under `artifacts/ui/latest/`. Once the sprint’s polish pass is approved, copy them into `artifacts/ui/reference/` (or rename the folder with the sprint tag) so future runs have a baseline.

## 2. Smoke test interactions

1. Launch the app on device/emulator.
2. Start/stop recording twice – verify the pulsing button + queued badge animation.
3. Trigger a summary refresh and ensure the spinner + snackbar appear.
4. Open settings, edit credentials, and confirm the save/test snackbars fire.

## 3. Review log output

- Inspect `~/.daymind/log.txt` (desktop) or `adb logcat` (Android) to ensure no red stack traces appear during the smoke run.

## 4. Archive evidence

- Store the generated PNGs + a short note (device, build hash) inside `artifacts/ui/README.md` or the release ticket.
- Keep the previous “golden” PNGs inside `artifacts/ui/reference/` so diffs are one `git status` away (no binaries in GitHub Releases needed).
- If regressions are spotted, add annotated screenshots to the issue so the next iteration can target the right widget.
