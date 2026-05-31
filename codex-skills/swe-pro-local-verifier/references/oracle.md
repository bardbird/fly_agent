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
- directly constructs a new public config type with gold-only field names when alternate names would satisfy the issue
- asserts exact internal call order or private state absent from the issue
- asserts validation order when the issue only requires validation before external side effects
- checks implementation-specific error text when the public contract only requires failure
- mixes unrelated refactors or formatting churn into the hidden test
- uses flaky timing, external services, or environment-local paths

When rewriting, preserve the smallest black-box behavior contract that distinguishes base from fixed.

## Alternate Implementation Probe

Before accepting a rewritten oracle, answer these questions:

- Would the test compile if the model used a different helper/function name?
- Would the test compile if a new config field were named `Hostname` or `CustomDomain` instead of the reference `Domain`?
- Would the test fail only because validation checks happen in a different order?
- Could the assertion be made through a public factory, CLI, API, or behavior boundary instead of a reference-internal type?

If the answer to any question exposes a valid alternate solution, rewrite the test. Reflection or table helpers are acceptable in Go tests when they avoid overfitting new field names while still checking public behavior.

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
- check whether the selected test package imports a broad dependency graph that exposes unrelated Docker-context omissions; if so, fix the context/toolkit issue or move the oracle to a narrower public boundary without overfitting
- check whether dependencies belong in Docker/runtime setup rather than test code

Do not edit production code inside `repo/` directly; update patches.
