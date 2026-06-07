#!/usr/bin/env bash
(
set -euo pipefail

mysql=(mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4)

if [[ -n "${XXL_JOB_ADMIN_PASSWORD_HASH:-}" ]]; then
    admin_password_hash="${XXL_JOB_ADMIN_PASSWORD_HASH}"
elif [[ -n "${XXL_JOB_ADMIN_PASSWORD:-}" ]]; then
    admin_password_hash="$(printf '%s' "${XXL_JOB_ADMIN_PASSWORD}" | md5sum | awk '{print $1}')"
else
    admin_password="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16)"
    admin_password_hash="$(printf '%s' "${admin_password}" | md5sum | awk '{print $1}')"
    echo "Generated XXL-Job admin password for initial database: ${admin_password}"
fi

"${mysql[@]}" xxl_job <<'SQL'
INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'fly-agent-executor', 'Fly Agent', 0, NULL, NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM xxl_job_group
    WHERE app_name = 'fly-agent-executor'
);

UPDATE xxl_job_group
SET title = 'Fly Agent',
    address_type = 0,
    address_list = NULL,
    update_time = NOW()
WHERE app_name = 'fly-agent-executor';
SQL

"${mysql[@]}" xxl_job -e "
UPDATE xxl_job_user
SET password = '${admin_password_hash}'
WHERE username = 'admin';
"

"${mysql[@]}" xxl_job < /fly-agent-scripts/upsert-swe-xxl-language-jobs.sql

"${mysql[@]}" xxl_job -e "
SELECT app_name, title, address_type FROM xxl_job_group WHERE app_name = 'fly-agent-executor';
SELECT executor_handler, COUNT(*) AS job_count
FROM xxl_job_info
WHERE executor_handler IN ('sweRepoScaDiscoveryJob', 'sweRepoCandidateBackfillJob')
GROUP BY executor_handler;
"
)
