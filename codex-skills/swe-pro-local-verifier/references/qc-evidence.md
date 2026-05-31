# QC Evidence

Backend QC rejects review files containing unresolved template placeholders.

Rejected needles include:

- `PENDING_`
- `待审校`
- `待补充`
- `待评测`
- `待验证`
- template sentences such as `完成真实性与问题陈述核对后填写`

The task initializer writes placeholder review files by design. The local verifier must replace them before handoff or before resuming backend execution.

Use:

```bash
python3 codex-skills/swe-pro-local-verifier/scripts/qc_review_evidence.py <package_dir>
python3 codex-skills/swe-pro-local-verifier/scripts/qc_review_evidence.py <package_dir> --check-only
```

Rules:

- Generate review evidence after oracle review and Docker validation pass.
- The three reviewer files must include a second-level `## 人员背景` section. Use `qc_review_evidence.py` to write the approved reviewer backgrounds.
- Run the script again after model evaluation summaries exist if the package is paused before QC.
- Do not use forbidden placeholder words to mean "not started"; write concrete operational status instead.
- Keep reviewer files factual. If model summaries are not present at local handoff, state that backend model stages must produce `model_evaluation/*/summary.json` before package export.
- Always run `--check-only` before calling backend resume/start.
