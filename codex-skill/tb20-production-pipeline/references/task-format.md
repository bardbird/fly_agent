# TB 2.0 Task Format Reference

Required files:

- `task.toml`: metadata, verifier timeout, agent timeout, environment resources.
- `instruction.md`: task statement shown to the agent.
- `environment/Dockerfile`: initial container environment.
- `solution/solve.sh`: reference solution/oracle path.
- `tests/test.sh`: verifier entrypoint.
- `tests/test_outputs.py`: concrete assertions, usually pytest.

Enhanced delivery logs:

- `agent-logs/run.json`: run summary and token/runtime metrics.
- `agent-logs/trajectory.json`: ATIF trajectory, if available.
- `agent-logs/verifier/ctrf.json`: CTRF test report, if available.
- `agent-logs/verifier/reward.txt`: final reward, if available.

Quality checks:

- `instruction.md` requirements should map to assertions in tests.
- `solution/solve.sh` must not depend on hidden local state.
- `tests/test_outputs.py` should verify behavior, not only file existence.
- Fixed-input tasks are allowed, but hardcoding risk must be explicit.
