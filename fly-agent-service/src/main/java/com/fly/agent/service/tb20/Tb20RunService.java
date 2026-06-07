package com.fly.agent.service.tb20;

import com.alibaba.fastjson2.JSON;
import com.fly.agent.common.dto.tb20.Tb20DatasetRunRequest;
import com.fly.agent.common.dto.tb20.Tb20ExecutionRunRequest;
import com.fly.agent.common.dto.tb20.Tb20RunArtifactDTO;
import com.fly.agent.common.dto.tb20.Tb20RunResponse;
import com.fly.agent.common.dto.tb20.Tb20RunStageStatusDTO;
import com.fly.agent.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class Tb20RunService {

    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Map<String, List<String>> DOMAIN_CHANNELS = Map.of(
            "software-engineering", List.of("github-pr-mining", "software-heritage", "libraries-io"),
            "system-administration", List.of("debian-source", "linux-man-pages", "systemd-repo", "kubernetes-repo"),
            "security", List.of("nvd-api", "cve-cvelist", "cwe", "exploit-db", "vulhub"),
            "data-science", List.of("uci-ml", "openml", "data-gov", "common-crawl-discovery"),
            "scientific-computing", List.of("netlib", "nist-strd", "suitesparse", "scipy-numpy-tests"),
            "file-operations", List.of("coreutils", "libarchive", "rsync", "debian-archive-docs", "posix-spec"),
            "web-network-services", List.of("rfc-editor", "iana-registries", "w3c-whatwg", "curl-tests", "apache-nginx-docs"),
            "distributed-systems", List.of("cncf-landscape", "kubernetes-repo", "etcd-repo", "prometheus-repo", "jepsen-analyses"),
            "performance-optimization", List.of("llvm-test-suite", "google-benchmark", "phoronix-test-suite", "open-polybench"),
            "algorithms-and-formats", List.of("rfc-iana", "netlib", "rosetta-code", "cp-algorithms", "format-spec-repos"));

    private final Tb20Properties properties;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public Tb20RunResponse startDatasetRun(Tb20DatasetRunRequest request) {
        validateDatasetRequest(request);
        String runId = "dataset-" + RUN_TIME.format(LocalDateTime.now()) + "-" + shortId();
        Path workspace = runWorkspace(request.getWorkspaceRoot(), "dataset-production-runs", runId);
        String outputRoot = firstText(request.getOutputRoot(), workspace.resolve("output").toString());
        Tb20RunResponse run = createRun(runId, "DATASET_PRODUCTION", "tb20-dataset-production", workspace, outputRoot);
        run.setStages(List.of(
                stage("SOURCE_CHANNEL_CHECK", "渠道与许可证配置检查"),
                stage("PREPARE_INSTRUCTION", "结构化 instruction 生产准备"),
                stage("BLOCK_OR_HANDOFF", "阻断或交给后续 agent adapter")
        ));
        run.setCommand(datasetCommand(request, workspace, outputRoot));
        persistRun(run);
        launch(run);
        return run;
    }

    public Tb20RunResponse startExecutionRun(Tb20ExecutionRunRequest request) {
        validateExecutionRequest(request);
        String runId = "execution-" + RUN_TIME.format(LocalDateTime.now()) + "-" + shortId();
        Path workspace = runWorkspace(request.getWorkspaceRoot(), "execution-runs", runId);
        String outputRoot = firstText(request.getOutputRoot(), workspace.resolve("delivery").toString());
        Tb20RunResponse run = createRun(runId, "BATCH_EXECUTION_DELIVERY", "tb20-batch-execution-delivery", workspace, outputRoot);
        run.setStages(List.of(
                stage("INIT", "初始化执行 workspace"),
                stage("DEPS", "依赖检查"),
                stage("INSPECT", "数据集结构检查"),
                stage("RUN", "Harbor agent 批量执行"),
                stage("COLLECT", "真实 agent-logs 收集"),
                stage("PACKAGE", "demo 对齐交付打包"),
                stage("AUDIT", "交付审计")
        ));
        run.setCommand(executionShellCommand(request, workspace, outputRoot));
        persistRun(run);
        launch(run);
        return run;
    }

    public Tb20RunResponse getRun(String runId) {
        return readRun(runId);
    }

    public List<Tb20RunResponse> listRuns() {
        Path root = resolveLocalPath(properties.getProductionRoot(), "tb20-output");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, 5)) {
            return stream
                    .filter(path -> path.getFileName().toString().equals("run.json"))
                    .map(this::tryReadRunFile)
                    .filter(Objects::nonNull)
                    .filter(run -> StringUtils.hasText(run.getRunId()))
                    .sorted(Comparator.comparing(Tb20RunResponse::getStartedAt, Comparator.nullsLast(String::compareTo)).reversed())
                    .toList();
        } catch (IOException e) {
            throw new BusinessException("failed to list TB20 runs", e);
        }
    }

    public String readLog(String runId) {
        Tb20RunResponse run = readRun(runId);
        Path log = Path.of(run.getLogPath());
        if (!Files.exists(log)) {
            return "";
        }
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("failed to read TB20 run log", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private Tb20RunResponse createRun(String runId, String kind, String skillName, Path workspace, String outputRoot) {
        try {
            Files.createDirectories(workspace);
            Files.createDirectories(Path.of(outputRoot));
            Tb20RunResponse run = new Tb20RunResponse();
            run.setRunId(runId);
            run.setKind(kind);
            run.setStatus("RUNNING");
            run.setSkillName(skillName);
            run.setWorkspace(workspace.toString());
            run.setOutputRoot(outputRoot);
            run.setLogPath(workspace.resolve("run.log").toString());
            run.setStartedAt(ISO_TIME.format(LocalDateTime.now()));
            run.setArtifacts(defaultArtifacts(workspace, outputRoot));
            return run;
        } catch (IOException e) {
            throw new BusinessException("failed to create TB20 run workspace", e);
        }
    }

    private void launch(Tb20RunResponse run) {
        executor.submit(() -> {
            try {
                Path logPath = Path.of(run.getLogPath());
                Files.createDirectories(logPath.getParent());
                Files.writeString(logPath, "$ " + String.join(" ", run.getCommand()) + "\n", StandardCharsets.UTF_8);
                ProcessBuilder builder = new ProcessBuilder(run.getCommand());
                builder.directory(projectRoot().toFile());
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
                Process process = builder.start();
                int exitCode = process.waitFor();
                run.setExitCode(exitCode);
                run.setStatus(exitCode == 0 ? "COMPLETED" : exitCode == 2 ? "BLOCKED" : "FAILED");
                finishStages(run, exitCode);
            } catch (Exception e) {
                run.setStatus("FAILED");
                run.setErrorMessage(e.getMessage());
                failStages(run, e.getMessage());
            } finally {
                run.setFinishedAt(ISO_TIME.format(LocalDateTime.now()));
                run.setArtifacts(defaultArtifacts(Path.of(run.getWorkspace()), run.getOutputRoot()));
                persistRun(run);
            }
        });
    }

    private List<String> datasetCommand(Tb20DatasetRunRequest request, Path workspace, String outputRoot) {
        List<String> command = new ArrayList<>();
        command.add(resolveLocalPath("codex-skills/tb20-dataset-production/.venv/bin/python", properties.getPython()).toString());
        command.add(resolveLocalPath("codex-skills/tb20-dataset-production/scripts/tb20_dataset.py", "codex-skills/tb20-dataset-production/scripts/tb20_dataset.py").toString());
        command.add("prepare-instruction");
        command.add("--workspace");
        command.add(workspace.toString());
        command.add("--output-root");
        command.add(outputRoot);
        command.add("--domain");
        command.add(request.getDomain());
        command.add("--source-channel");
        command.add(request.getSourceChannel());
        command.add("--brief");
        command.add(firstText(request.getBrief(), ""));
        command.add("--channel-config");
        command.add(request.getChannelConfig() == null ? "{}" : request.getChannelConfig().toJSONString());
        return command;
    }

    private List<String> executionShellCommand(Tb20ExecutionRunRequest request, Path workspace, String outputRoot) {
        Path python = resolveLocalPath("codex-skills/tb20-batch-execution-delivery/.venv/bin/python", properties.getPython());
        Path script = resolveLocalPath("codex-skills/tb20-batch-execution-delivery/scripts/tb20_execute.py", "codex-skills/tb20-batch-execution-delivery/scripts/tb20_execute.py");
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add("-lc");
        String taskArgs = "";
        if (request.getTaskPaths() != null) {
            for (String task : request.getTaskPaths()) {
                if (StringUtils.hasText(task)) {
                    taskArgs += " --task " + shellQuote(task);
                }
            }
        }
        String modelArgs = StringUtils.hasText(request.getModel()) ? " --model " + shellQuote(request.getModel()) : "";
        String failFast = Boolean.TRUE.equals(request.getFailFast()) ? " --fail-fast" : "";
        String concurrency = request.getConcurrency() == null ? "1" : request.getConcurrency().toString();
        String dockerMirrors = request.getExecutionConfig() == null ? "" : firstText(request.getExecutionConfig().getString("dockerRegistryMirrors"), "");
        String aptMirror = request.getExecutionConfig() == null ? "" : firstText(request.getExecutionConfig().getString("aptMirror"), "");
        String pythonIndexUrl = request.getExecutionConfig() == null ? "" : firstText(request.getExecutionConfig().getString("pythonIndexUrl"), "");
        String command = String.join(" && ",
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " init --workspace " + shellQuote(workspace.toString()) + " --source-root " + shellQuote(request.getSourceRoot()) + " --output-root " + shellQuote(outputRoot)
                        + " --docker-registry-mirrors " + shellQuote(dockerMirrors)
                        + " --apt-mirror " + shellQuote(aptMirror)
                        + " --python-index-url " + shellQuote(pythonIndexUrl),
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " deps --workspace " + shellQuote(workspace.toString()),
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " inspect --workspace " + shellQuote(workspace.toString()) + taskArgs,
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " run --workspace " + shellQuote(workspace.toString()) + " --agent " + shellQuote(firstText(request.getAgent(), "claude-code")) + " --concurrency " + shellQuote(concurrency) + modelArgs + taskArgs + failFast,
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " collect --workspace " + shellQuote(workspace.toString()) + taskArgs,
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " package --workspace " + shellQuote(workspace.toString()),
                shellQuote(python.toString()) + " " + shellQuote(script.toString()) + " audit --workspace " + shellQuote(workspace.toString())
        );
        cmd.add(command);
        return cmd;
    }

    private List<Tb20RunArtifactDTO> defaultArtifacts(Path workspace, String outputRoot) {
        List<Tb20RunArtifactDTO> artifacts = new ArrayList<>();
        artifacts.add(artifact("Run log", "stdout/stderr", workspace.resolve("run.log")));
        artifacts.add(artifact("Run state", "backend state", workspace.resolve("run.json")));
        artifacts.add(artifact("Output root", "skill output", Path.of(outputRoot)));
        return artifacts;
    }

    private Tb20RunArtifactDTO artifact(String name, String role, Path path) {
        Tb20RunArtifactDTO artifact = new Tb20RunArtifactDTO();
        artifact.setName(name);
        artifact.setRole(role);
        artifact.setPath(path.toString());
        artifact.setPresent(Files.exists(path));
        return artifact;
    }

    private Tb20RunStageStatusDTO stage(String code, String name) {
        Tb20RunStageStatusDTO stage = new Tb20RunStageStatusDTO();
        stage.setCode(code);
        stage.setName(name);
        stage.setStatus("PENDING_SCRIPT");
        stage.setNote("由后端结构化命令和脚本门禁控制");
        return stage;
    }

    private void finishStages(Tb20RunResponse run, int exitCode) {
        if (exitCode == 0) {
            run.getStages().forEach(stage -> stage.setStatus("PASS"));
            return;
        }
        String status = exitCode == 2 ? "BLOCKED" : "FAILED";
        for (Tb20RunStageStatusDTO stage : run.getStages()) {
            if ("PENDING_SCRIPT".equals(stage.getStatus())) {
                stage.setStatus(status);
            }
        }
    }

    private void failStages(Tb20RunResponse run, String reason) {
        for (Tb20RunStageStatusDTO stage : run.getStages()) {
            if ("PENDING_SCRIPT".equals(stage.getStatus())) {
                stage.setStatus("FAILED");
                stage.setNote(reason);
            }
        }
    }

    private void validateDatasetRequest(Tb20DatasetRunRequest request) {
        String domain = firstText(request.getDomain(), "");
        String channel = firstText(request.getSourceChannel(), "");
        List<String> channels = DOMAIN_CHANNELS.get(domain);
        if (channels == null) {
            throw new BusinessException("TB20 domain is not allowed: " + domain);
        }
        if (!channels.contains(channel)) {
            throw new BusinessException("TB20 sourceChannel is not allowed for " + domain + ": " + channel);
        }
    }

    private void validateExecutionRequest(Tb20ExecutionRunRequest request) {
        if (!StringUtils.hasText(request.getSourceRoot())) {
            throw new BusinessException("sourceRoot不能为空");
        }
        Path sourceRoot = resolveLocalPath(request.getSourceRoot(), request.getSourceRoot());
        if (!Files.isDirectory(sourceRoot)) {
            throw new BusinessException("sourceRoot不存在或不是目录: " + sourceRoot);
        }
        int concurrency = request.getConcurrency() == null ? 1 : request.getConcurrency();
        if (concurrency < 1 || concurrency > 16) {
            throw new BusinessException("concurrency必须在1到16之间");
        }
    }

    private void persistRun(Tb20RunResponse run) {
        try {
            Path path = Path.of(run.getWorkspace()).resolve("run.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.toJSONString(run), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("failed to persist TB20 run", e);
        }
    }

    private Tb20RunResponse readRun(String runId) {
        Path root = resolveLocalPath(properties.getProductionRoot(), "tb20-output");
        if (!Files.isDirectory(root)) {
            throw new BusinessException("TB20 run not found: " + runId);
        }
        try (Stream<Path> stream = Files.walk(root, 5)) {
            return stream
                    .filter(path -> path.getFileName().toString().equals("run.json"))
                    .map(this::tryReadRunFile)
                    .filter(Objects::nonNull)
                    .filter(run -> StringUtils.hasText(run.getRunId()))
                    .filter(run -> runId.equals(run.getRunId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("TB20 run not found: " + runId));
        } catch (IOException e) {
            throw new BusinessException("failed to read TB20 run", e);
        }
    }

    private Tb20RunResponse tryReadRunFile(Path path) {
        try {
            return JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8), Tb20RunResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path runWorkspace(String configuredRoot, String fallbackSubdir, String runId) {
        Path root = StringUtils.hasText(configuredRoot)
                ? resolveLocalPath(configuredRoot, configuredRoot)
                : resolveLocalPath(properties.getProductionRoot(), "tb20-output").resolve("runs").resolve(fallbackSubdir);
        return root.resolve(runId).toAbsolutePath().normalize();
    }

    private Path projectRoot() {
        return resolveLocalPath(".", ".");
    }

    private Path resolveLocalPath(String configuredPath, String fallbackPath) {
        String text = StringUtils.hasText(configuredPath) ? configuredPath : fallbackPath;
        Path path = Path.of(text);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Path current = cwd;
        while (current != null) {
            Path candidate = current.resolve(path).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return cwd.resolve(path).normalize();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
