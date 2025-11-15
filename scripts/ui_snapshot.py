"""Utility to capture DayMind UI screenshots for visual QA."""

from __future__ import annotations

import argparse
from pathlib import Path

from kivy.clock import Clock
from kivy.core.window import Window

from mobile.daymind.app import DayMindApp


class SnapshotApp(DayMindApp):
    def __init__(self, output_dir: Path, **kwargs):
        super().__init__(**kwargs)
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._screens = [
            ("record", "record_screen.png"),
            ("summary", "summary_screen.png"),
            ("settings", "settings_screen.png"),
        ]

    def on_start(self):
        super().on_start()
        Clock.schedule_once(lambda dt: self._capture(0), 1.2)

    def _capture(self, index: int) -> None:
        if index >= len(self._screens):
            self.stop()
            return
        screen_name, filename = self._screens[index]
        self.switch_screen(screen_name)
        Clock.schedule_once(lambda dt: self._snap(filename, index + 1), 0.8)

    def _snap(self, filename: str, next_index: int) -> None:
        Window.screenshot(name=str(self.output_dir / filename))
        self._capture(next_index)


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture DayMind UI screenshots.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("artifacts/ui/latest"),
        help="Directory to store generated PNG screenshots (default: artifacts/ui/latest).",
    )
    args = parser.parse_args()
    SnapshotApp(output_dir=args.output).run()


if __name__ == "__main__":
    main()
