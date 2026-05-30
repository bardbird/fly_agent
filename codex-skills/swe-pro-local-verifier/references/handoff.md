# Handoff Evidence

Write `.swe-ai/handoff.json` after Docker local verification passes.

Required fields:

```json
{
  "status": "local_verification_passed",
  "package_path": "/abs/path/to/package",
  "task_id": null,
  "candidate_id": null,
  "run_id": null,
  "resume_from_stage": "MODEL_OPUS_EVAL",
  "verified_at": "2026-05-30T00:00:00Z",
  "commands": {
    "patch_application": "bash scripts/verify_patch_application.sh",
    "docker_verify": "python3 tools/swe-pro-production/scripts/package_task.py <package_dir> --docker",
    "eval_shell_parity": "bash -lc '<before_repo_set_cmd>'; bash -lc 'command -v <language-tool>'"
  },
  "oracle_fairness": {
    "status": "passed",
    "notes": []
  },
  "eval_shell_parity": {
    "status": "passed",
    "notes": []
  },
  "model_eval_parameters": {
    "resume_from_stage": "MODEL_OPUS_EVAL",
    "attempts": null,
    "timeout_seconds": null,
    "agent_max_steps": null,
    "agent_max_steps_schedule": null
  },
  "artifacts": {
    "validation_json": "logs/docker/validation.json",
    "docker_build_log": "logs/docker_build.log",
    "baseline_log": "logs/docker/baseline.log",
    "fixed_log": "logs/docker/fixed.log",
    "pass_to_pass_log": "logs/docker/pass_to_pass.log"
  },
  "docker": {
    "image_tag": "",
    "image_size_bytes": 0,
    "image_size_gib": 0.0,
    "budget_status": "ok"
  },
  "api_call_budget": {
    "local_verification_calls": 0,
    "handoff_calls_expected": "1-3 excluding polling"
  },
  "notes": []
}
```

Also update `verification.md` with the observed results and mention the exact package path.
