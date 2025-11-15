"""Pytest configuration helpers."""

from __future__ import annotations

import sys
from pathlib import Path


def _ensure_repo_on_path() -> None:
    """Allow tests to import from repo modules without setting PYTHONPATH."""
    repo_root = Path(__file__).resolve().parents[1]
    path_str = str(repo_root)
    if path_str not in sys.path:
        sys.path.insert(0, path_str)


_ensure_repo_on_path()
