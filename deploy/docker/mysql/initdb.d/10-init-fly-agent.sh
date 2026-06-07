#!/usr/bin/env bash
(
set -euo pipefail

mysql=(mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4)

"${mysql[@]}" <<'SQL'
CREATE DATABASE IF NOT EXISTS fly_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL

while IFS= read -r migration; do
    echo "Applying fly_agent migration: ${migration}"
    "${mysql[@]}" fly_agent < "${migration}"
done < <(find /fly-agent-migrations -maxdepth 1 -type f -name 'V*.sql' | sort -V)

if [[ -s /fly-agent-seed/swe_repo_blacklist.sql ]]; then
    echo "Importing fly_agent seed: swe_repo_blacklist.sql"
    "${mysql[@]}" fly_agent < /fly-agent-seed/swe_repo_blacklist.sql
fi

"${mysql[@]}" fly_agent -e "SHOW TABLES;"
)
