#!/usr/bin/env bash
# Install repo codex-skills into the host's ~/.codex/skills/ directory.
# Run this on the swe server after git pull.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILLS_SRC="$REPO_ROOT/codex-skills"
SKILLS_DST="${CODEX_HOME:-$HOME/.codex}/skills"

mkdir -p "$SKILLS_DST"

for skill_dir in "$SKILLS_SRC"/*/; do
    name="$(basename "$skill_dir")"
    # Skip non-skill dirs
    [[ -f "$skill_dir/SKILL.md" ]] || continue

    echo "Installing skill: $name"
    rm -rf "$SKILLS_DST/$name"
    cp -r "$skill_dir" "$SKILLS_DST/$name"
    echo "  -> $SKILLS_DST/$name"
done

# Clear Codex daemon state to pick up new skills
rm -rf "${CODEX_HOME:-$HOME/.codex}/daemon" \
       "${CODEX_HOME:-$HOME/.codex}/sessions" \
       "${CODEX_HOME:-$HOME/.codex}/session-env" 2>/dev/null || true

echo "Done. Restart any running codex sessions to pick up new skills."
