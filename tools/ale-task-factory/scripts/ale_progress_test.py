from __future__ import annotations
import json
from pathlib import Path

from ale_progress import write_progress, is_terminal


def test_write_progress_atomic_and_readable(tmp_path: Path):
    p = tmp_path / "stage1_progress.json"
    write_progress(p, stage="stage1", phase="starting", percent=0, counts={"total": 1})
    assert json.loads(p.read_text(encoding="utf-8")) == {
        "stage": "stage1", "phase": "starting", "percent": 0,
        "counts": {"total": 1}, "current_task": None,
        "message": None,
    }
    # 原子写：不留 .tmp 残留
    assert not (tmp_path / "stage1_progress.json.tmp").exists()


def test_write_progress_overwrites_previous_terminal(tmp_path: Path):
    p = tmp_path / "stage2_progress.json"
    write_progress(p, stage="stage2", phase="done", percent=100,
                   counts={"total": 2, "completed": 2})
    write_progress(p, stage="stage2", phase="starting", percent=0,
                   counts={"total": 2})
    data = json.loads(p.read_text(encoding="utf-8"))
    assert data["phase"] == "starting"  # 新值覆盖旧 done（gateway 重跑重置语义）


def test_is_terminal():
    assert is_terminal("done") is True
    assert is_terminal("failed") is True
    assert is_terminal("codex_running") is False
    assert is_terminal(None) is False
