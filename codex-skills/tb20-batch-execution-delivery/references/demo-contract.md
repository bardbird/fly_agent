# TB2.0 Execution Delivery Contract

Input task source files:

```text
task.toml
instruction.md
environment/Dockerfile
solution/solve.sh
tests/test.sh
tests/test_outputs.py
```

Execution output files required for final delivery:

```text
agent-logs/run.json
agent-logs/trajectory.json
agent-logs/verifier/ctrf.json
agent-logs/verifier/reward.txt
```

Delivery root layout:

```text
README.md
README_zh.md
easy/<task-name>/
medium/<task-name>/
hard/<task-name>/
```

Do not include workspace evidence, Harbor job directories, transcripts, or manifests inside delivered task directories.
