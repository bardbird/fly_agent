# Oracle Review

Review `test.patch` before spending time on Docker.

## Accept

Accept the oracle when tests:

- assert public behavior described by the issue or PR-linked issue
- can pass with alternative valid implementations
- fail on the base commit for the intended behavior
- pass after applying `gold.patch`
- avoid network, wall-clock, random, or machine-specific assumptions
- keep snapshots/goldens limited to issue-visible output

## Rewrite

Rewrite `test.patch` when it contains useful coverage but also:

- imports new helper functions/classes introduced only by `gold.patch`
- asserts exact internal call order or private state absent from the issue
- checks implementation-specific error text when the public contract only requires failure
- mixes unrelated refactors or formatting churn into the hidden test
- uses flaky timing, external services, or environment-local paths

When rewriting, preserve the smallest black-box behavior contract that distinguishes base from fixed.

## Reject

Reject the candidate when:

- the issue text does not support the behavior under test
- tests only validate the reference implementation structure
- no reliable baseline failure can be produced
- the PR has no meaningful testable user-visible behavior
- the hidden oracle requires private credentials, hosted services, or non-reproducible data

## Baseline Failure Diagnosis

If `baseline` unexpectedly passes:

- confirm `test.patch` actually applied
- confirm the selected test command runs the new tests
- check whether the base commit already contains the fix
- check whether the assertion is too weak
- check whether the test is skipped by marker, platform, or dependency condition

Do not make `baseline` fail by adding assertions unrelated to the issue.

## Fixed Failure Diagnosis

If `fixed` fails:

- read the compile/runtime error first
- check whether test imports match project conventions
- check whether generated snapshots need stable normalization
- check whether test setup needs an existing fixture
- check whether `gold.patch` and `test.patch` conflict
- check whether dependencies belong in Docker/runtime setup rather than test code

Do not edit production code inside `repo/` directly; update patches.
