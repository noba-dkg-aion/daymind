"""Persistent settings storage for server URL and API key."""

from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional


@dataclass(slots=True)
class AppSettings:
    server_url: str = ""
    api_key: str = ""
    vad_threshold: int = 3500
    vad_aggressiveness: int = 2
    noise_gate: float = 0.12


class SettingsStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._settings = self._load()

    @property
    def _path(self) -> Path:
        return self.path

    def _load(self) -> AppSettings:
        if not self._path.exists():
            return AppSettings()
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
        except Exception:
            raw = {}
        settings = AppSettings()
        settings.server_url = str(raw.get("server_url", ""))
        settings.api_key = str(raw.get("api_key", ""))
        settings.vad_threshold = int(raw.get("vad_threshold", settings.vad_threshold))
        settings.vad_aggressiveness = int(raw.get("vad_aggressiveness", settings.vad_aggressiveness))
        settings.noise_gate = float(raw.get("noise_gate", settings.noise_gate))
        return settings

    def get(self) -> AppSettings:
        return self._settings

    def update(self, **kwargs) -> AppSettings:
        for key, value in kwargs.items():
            if not hasattr(self._settings, key):
                continue
            current = getattr(self._settings, key)
            if isinstance(current, float):
                setattr(self._settings, key, float(value))
            elif isinstance(current, int):
                setattr(self._settings, key, int(value))
            else:
                setattr(self._settings, key, value or "")
        self._persist()
        return self._settings

    def _persist(self) -> None:
        self._path.write_text(json.dumps(asdict(self._settings)), encoding="utf-8")
