# Implementation Review

The Dockerfile generates all fixtures during image build under `/app`. The reference solution uses only Python standard-library code. The verifier is a Python script called by `tests/test.sh`, writes reward through the standard Terminal-Bench verifier path, and does not synthesize success.
