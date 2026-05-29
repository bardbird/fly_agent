#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker/docker-compose.yml"
DEFAULT_ENV_FILE="$ROOT_DIR/deploy/docker/.env"
ENV_EXAMPLE="$ROOT_DIR/deploy/docker/.env.example"
ENV_FILE="${FLY_AGENT_DOCKER_ENV:-$DEFAULT_ENV_FILE}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-fly-agent}"

usage() {
    cat <<'EOF'
Usage: scripts/docker-deploy.sh <command> [service...]

Commands:
  init-env             Create deploy/docker/.env from .env.example if missing
  bootstrap-swe-agent  Clone/install SWE-agent under SWE_AGENT_HOST_ROOT
  build [service...]   Build images
  start [service...]   Start services in the background
  deploy [service...]  Build, start, then remove old project images
  stop [service...]    Stop running services
  restart [service...] Restart services
  down                 Stop and remove containers for this stack
  status               Show container status
  logs [service...]    Follow logs
  clean-images         Remove dangling images and old fly-agent app images
  clean-all-images     Remove all fly-agent app images not used by running containers
  config               Render Docker Compose config

Services:
  fly-agent-web fly-agent-server fly-agent-task
EOF
}

init_env() {
    if [[ ! -f "$ENV_FILE" ]]; then
        cp "$ENV_EXAMPLE" "$ENV_FILE"
        echo "Created $ENV_FILE from .env.example. Review secrets and endpoints before production use."
    fi
}

host_run_uid() {
    if [[ "$(id -u)" == "0" && -n "${SUDO_UID:-}" ]]; then
        printf '%s\n' "$SUDO_UID"
        return
    fi
    id -u
}

host_run_gid() {
    if [[ "$(id -u)" == "0" && -n "${SUDO_GID:-}" ]]; then
        printf '%s\n' "$SUDO_GID"
        return
    fi
    id -g
}

env_value() {
    local key="$1"
    local fallback="${2:-}"
    local line
    line="$(grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | tail -n 1 || true)"
    if [[ -z "$line" ]]; then
        printf '%s\n' "$fallback"
        return
    fi
    local value="${line#*=}"
    value="${value%\"}"
    value="${value#\"}"
    value="${value%\'}"
    value="${value#\'}"
    printf '%s\n' "$value"
}

docker_socket_gid() {
    local sock
    sock="$(env_value DOCKER_SOCK /var/run/docker.sock)"
    if [[ -e "$sock" ]]; then
        stat -c '%g' "$sock"
        return
    fi
    if getent group docker >/dev/null 2>&1; then
        getent group docker | awk -F: '{print $3}'
        return
    fi
    host_run_gid
}

ensure_env_default() {
    local key="$1"
    local value="$2"
    if grep -Eq "^${key}=" "$ENV_FILE" 2>/dev/null; then
        return
    fi
    printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
}

ensure_runtime_identity_env() {
    ensure_env_default FLY_AGENT_RUN_UID "$(host_run_uid)"
    ensure_env_default FLY_AGENT_RUN_GID "$(host_run_gid)"
    ensure_env_default DOCKER_GID "$(docker_socket_gid)"
}

run_root_cmd() {
    if [[ "$(id -u)" == "0" ]]; then
        "$@"
        return
    fi
    if command -v sudo >/dev/null 2>&1; then
        sudo "$@"
        return
    fi
    echo "Cannot run privileged command: $*" >&2
    echo "Install sudo or create/chown the deployment directories manually." >&2
    exit 1
}

ensure_owned_dir() {
    local dir="$1"
    local uid gid current_owner
    uid="$(env_value FLY_AGENT_RUN_UID "$(host_run_uid)")"
    gid="$(env_value FLY_AGENT_RUN_GID "$(host_run_gid)")"

    if [[ ! -d "$dir" ]]; then
        mkdir -p "$dir" 2>/dev/null || run_root_cmd mkdir -p "$dir"
    fi

    current_owner="$(stat -c '%u:%g' "$dir")"
    if [[ "$current_owner" != "$uid:$gid" ]]; then
        run_root_cmd chown "$uid:$gid" "$dir"
    fi

    chmod u+rwx,g+rwx "$dir" 2>/dev/null || run_root_cmd chmod u+rwx,g+rwx "$dir"

    if find "$dir" -xdev \( ! -uid "$uid" -o ! -gid "$gid" \) -print -quit 2>/dev/null | grep -q .; then
        echo "Repairing entries not owned by $uid:$gid under $dir"
        run_root_cmd find "$dir" -xdev \( ! -uid "$uid" -o ! -gid "$gid" \) -exec chown -h "$uid:$gid" {} +
    fi
}

python_for_swe_agent() {
    local candidate
    for candidate in python3.12 python3.11 python3; do
        if command -v "$candidate" >/dev/null 2>&1 \
            && "$candidate" - <<'PY' >/dev/null 2>&1
import sys
raise SystemExit(0 if sys.version_info >= (3, 11) else 1)
PY
        then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "SWE-agent requires Python 3.11+. Install python3.11 or python3.12 first." >&2
    exit 1
}

install_swe_agent_venv() {
    local root="$1"
    local python_bin
    python_bin="$(python_for_swe_agent)"
    rm -rf "$root/.venv"
    "$python_bin" -m venv "$root/.venv"
    "$root/.venv/bin/python" -m pip install --upgrade pip setuptools wheel
    "$root/.venv/bin/python" -m pip install -e "$root"
}

bootstrap_swe_agent() {
    local enabled
    enabled="$(env_value SWE_AGENT_BOOTSTRAP true)"
    if [[ "$enabled" == "false" || "$enabled" == "0" || "$enabled" == "no" ]]; then
        return
    fi

    local root url ref auto_update
    root="$(env_value SWE_AGENT_HOST_ROOT /opt/fly-agent/swe-agent)"
    url="$(env_value SWE_AGENT_GIT_URL https://github.com/SWE-agent/SWE-agent.git)"
    ref="$(env_value SWE_AGENT_GIT_REF main)"
    auto_update="$(env_value SWE_AGENT_AUTO_UPDATE false)"

    if [[ ! -d "$root/.git" ]]; then
        ensure_owned_dir "$(dirname "$root")"
        rm -rf "$root"
        git clone "$url" "$root"
    fi

    if [[ "$auto_update" == "true" || "$auto_update" == "1" || "$auto_update" == "yes" ]]; then
        git -C "$root" fetch --tags origin
        git -C "$root" checkout "$ref"
        git -C "$root" pull --ff-only origin "$ref" || true
    elif ! git -C "$root" rev-parse --verify --quiet "$ref^{commit}" >/dev/null; then
        git -C "$root" fetch --tags origin "$ref"
        git -C "$root" checkout "$ref"
    fi

    if [[ ! -x "$root/.venv/bin/sweagent" ]] || ! "$root/.venv/bin/sweagent" --help >/dev/null 2>&1; then
        install_swe_agent_venv "$root"
    fi
}

ensure_runtime_dirs() {
    ensure_owned_dir "$(env_value FLY_AGENT_DATA_DIR /data/fly-agent)"
    ensure_owned_dir "$(env_value SWE_OUTPUT_HOST_DIR /data/fly-agent/swe-output)"
    ensure_owned_dir "$(env_value FLY_AGENT_LOG_DIR /data/fly-agent/logs)"
    ensure_owned_dir "$(env_value XXL_JOB_LOG_DIR /data/fly-agent/logs/xxl-job)"
}

compose() {
    docker compose \
        --project-name "$PROJECT_NAME" \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" \
        "$@"
}

image_ids_for_project() {
    docker image ls --filter "label=com.fly-agent.stack=app" -q | sort -u
}

clean_images() {
    local remove_all="${1:-false}"
    image_ids_for_project | while read -r image_id; do
        [[ -n "$image_id" ]] || continue
        if [[ "$remove_all" == "true" ]] || [[ -z "$(docker ps -a --filter "ancestor=$image_id" -q)" ]]; then
            docker image rm "$image_id" || true
        fi
    done

    docker image prune -f
}

command="${1:-help}"
if [[ "$command" != "help" && "$command" != "-h" && "$command" != "--help" ]]; then
    init_env
    ensure_runtime_identity_env
fi

case "$command" in
    init-env)
        ;;
    bootstrap-swe-agent)
        bootstrap_swe_agent
        ;;
    build)
        shift
        bootstrap_swe_agent
        compose build "$@"
        ;;
    start)
        shift
        bootstrap_swe_agent
        ensure_runtime_dirs
        compose up -d --remove-orphans "$@"
        ;;
    deploy)
        shift
        bootstrap_swe_agent
        ensure_runtime_dirs
        compose build "$@"
        compose up -d --remove-orphans "$@"
        clean_images false
        ;;
    stop)
        shift
        compose stop "$@"
        ;;
    restart)
        shift
        compose restart "$@"
        ;;
    down)
        compose down --remove-orphans
        ;;
    status)
        compose ps
        ;;
    logs)
        shift
        compose logs -f --tail=200 "$@"
        ;;
    clean-images)
        clean_images false
        ;;
    clean-all-images)
        clean_images true
        ;;
    config)
        compose config
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
