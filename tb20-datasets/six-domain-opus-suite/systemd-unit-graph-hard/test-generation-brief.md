# Test Generation Brief

Observable outputs:
- See `instruction.md`.

Fixture plan:
- Generate deterministic synthetic fixtures in the Dockerfile.
- Keep source/license grounding separate from fixture values.

Normal behavior tests:
- Verify the declared output files, schema, sorting, and main semantic transformation.

Boundary tests:
- Cover the edge cases listed in `instruction.md`.

Adversarial/wrong-solution tests:
- Reject hard-coded or partial solutions that ignore sorting, edge cases, or malformed records.

Old behavior preservation tests:
- Not applicable for this standalone benchmark task.

Hidden-test strategy:
- Vary fixture values, ordering, and boundary rows while preserving the same input schema.

Wrong implementations to reject:
- Solutions that only create files without computing from inputs.
- Solutions that rely on external network data.
- Solutions that assume fixture order when the instruction requires sorting.
