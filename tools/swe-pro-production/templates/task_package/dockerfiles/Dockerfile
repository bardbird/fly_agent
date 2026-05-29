{{TOOLCHAIN_STAGES}}
FROM {{BASE_IMAGE}}

ENV DEBIAN_FRONTEND=noninteractive
ENV PIP_NO_CACHE_DIR=1
WORKDIR /workspace

{{TOOLCHAIN_COPY}}
RUN {{LANGUAGE_DEPENDENCIES_CMD}}

COPY repo/ /workspace/repo/
COPY scripts/ /workspace/scripts/
COPY patches/ /workspace/patches/
COPY task.json problem_statement.md README.md verification.md /workspace/

RUN git config --global --add safe.directory /workspace/repo \
    && cd /workspace/repo \
    && if [ ! -d .git ]; then git init -q && git config user.email "fly-agent@example.invalid" && git config user.name "fly-agent" && git add -A && git commit -q -m "baseline snapshot"; fi \
    && {{BEFORE_REPO_SET_CMD}} \
    && chmod +x /workspace/scripts/*.sh /workspace/scripts/parser.py

CMD ["bash", "/workspace/scripts/run_selected_tests.sh", "fixed"]
