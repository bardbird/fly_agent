# Oracle Positive

Oracle execution is performed by building the task image, running `solution/solve.sh` inside the task container, and then running `tests/test.sh` against the same container state. The generated solution computes the declared output from `/app` inputs and is expected to pass with reward `1`.

Required retained logs after oracle verification:
- `oracle-logs/build.log`
- `oracle-logs/solution.log`
- `oracle-logs/verifier.log`
- `oracle-logs/reward.txt`
- `oracle-logs/result.json`
