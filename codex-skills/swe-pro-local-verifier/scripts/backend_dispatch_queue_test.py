#!/usr/bin/env python3
from __future__ import annotations

import argparse
import contextlib
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backend_dispatch_queue as dispatch


class BackendDispatchQueueTest(unittest.TestCase):
    def test_candidate_db_query_uses_new_status_date_quality_and_task_exclusion(self) -> None:
        calls: list[tuple[str, dict]] = []

        def fake_get_json(base_url: str, path: str, params: dict) -> dict:
            calls.append((path, params))
            return {"candidates": [], "totalPages": 1}

        original = dispatch.get_json
        dispatch.get_json = fake_get_json
        try:
            args = argparse.Namespace(
                base_url="http://backend",
                candidate_db_page_size=50,
                candidate_statuses="scored,selected",
                duplicate_status="NEW",
                candidate_date="2026-05-31",
                candidate_from="",
                candidate_to="",
                candidate_date_field="created",
                min_score=70,
                min_gold_source_files=5,
                max_gold_source_files=100,
                min_gold_lines=108,
                max_gold_lines=1000,
            )

            result = dispatch.list_candidate_db_page(args, "python", 2)
        finally:
            dispatch.get_json = original

        self.assertEqual([], result["candidates"])
        self.assertEqual("/api/v1/swe/candidates", calls[0][0])
        params = calls[0][1]
        self.assertEqual(2, params["page"])
        self.assertEqual("scored,selected", params["candidateStatus"])
        self.assertEqual("NEW", params["duplicateStatus"])
        self.assertEqual("python", params["language"])
        self.assertEqual("created", params["dateField"])
        self.assertEqual("2026-05-31", params["dateFrom"])
        self.assertEqual("2026-05-31", params["dateTo"])
        self.assertTrue(params["qualifiedOnly"])
        self.assertTrue(params["excludeTasked"])
        self.assertTrue(params["testPatchPresent"])
        self.assertEqual(70, params["minScore"])

    def test_discover_validation_requires_limits_for_candidate_db_source(self) -> None:
        parser = argparse.ArgumentParser()
        args = argparse.Namespace(
            languages="go",
            source="candidate-db",
            candidate_date="2026-05-31",
            candidate_from="",
            candidate_to="",
            max_total_candidates=0,
            max_candidates_per_language=0,
            max_repos_per_language=0,
            candidate_limit_per_repo=0,
        )

        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                dispatch.validate_discover_args(parser, args)

        args.max_total_candidates = 3
        dispatch.validate_discover_args(parser, args)


if __name__ == "__main__":
    unittest.main()
