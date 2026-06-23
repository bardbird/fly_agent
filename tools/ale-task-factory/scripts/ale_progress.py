"""Shared progress writer for ALE runners.

Writes <stage>_progress.json atomically (tmp + replace) so the backend gateway
never reads a half-written file. Runner is the sole business writer of the
terminal phase (done/failed); see spec §4.3 / §8.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

TERMINAL_PHASES = ("done", "failed")


def is_terminal(phase: str | None) -> bool:
    return phase in TERMINAL_PHASES


def write_progress(
    path: Path,
    *,
    stage: str,
    phase: str,
    percent: int,
    counts: dict[str, int] | None = None,
    current_task: str | None = None,
    message: str | None = None,
) -> None:
    """Atomically write a progress frame. Overwrites any previous content."""
    payload: dict[str, Any] = {
        "stage": stage,
        "phase": phase,
        "percent": percent,
        "counts": counts or {"total": 0, "completed": 0, "failed": 0, "blocked": 0},
        "current_task": current_task,
        "message": message,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(tmp, path)  # atomic on POSIX & Windows
