package com.fly.agent.service.swe;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fly.agent.common.dto.swe.SweArtifactDTO;
import com.fly.agent.common.dto.swe.SweModelIoAttemptDTO;
import com.fly.agent.common.dto.swe.SweModelIoConsoleDTO;
import com.fly.agent.common.dto.swe.SweModelIoResponseDTO;
import com.fly.agent.common.dto.swe.SwePipelineRunDTO;
import com.fly.agent.common.dto.swe.SwePipelineStartRequest;
import com.fly.agent.common.dto.swe.SweStageDTO;
import com.fly.agent.common.dto.swe.SweTaskCreateRequest;
import com.fly.agent.common.dto.swe.SweTaskDTO;
import com.fly.agent.common.dto.swe.SweTaskFromCandidateRequest;
import com.fly.agent.common.enums.swe.SwePipelineStage;
import com.fly.agent.common.enums.swe.SwePipelineStatus;
import com.fly.agent.common.exception.BusinessException;
import com.fly.agent.dao.entity.swe.SweArtifactEntity;
import com.fly.agent.dao.entity.swe.SweCandidateEntity;
import com.fly.agent.dao.entity.swe.SwePipelineRunEntity;
import com.fly.agent.dao.entity.swe.SwePipelineStageEntity;
import com.fly.agent.dao.entity.swe.SweTaskEntity;
import com.fly.agent.dao.mapper.swe.SweArtifactMapper;
import com.fly.agent.dao.mapper.swe.SweCandidateMapper;
import com.fly.agent.dao.mapper.swe.SwePipelineRunMapper;
import com.fly.agent.dao.mapper.swe.SwePipelineStageMapper;
import com.fly.agent.dao.mapper.swe.SweTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SWE-Pro data production pipeline service.
 *
 * <p>The model gate runs Qwen and direct Opus pass@8 only. GPT-assisted Opus
 * retry prompts are not part of the production path.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwePipelineService {

    private static final String ERROR_TASK_NOT_FOUND = "SWE-Pro task not found";
    private static final int PACKAGE_PREPARE_MAX_ATTEMPTS = 3;
    private static final String GITHUB_PROXY_HOST = "127.0.0.1";
    private static final int GITHUB_PROXY_PORT = 7897;
    private static final int GITHUB_PROXY_CONNECT_TIMEOUT_MS = 500;
    private static final String DEFAULT_GO_PROXY = "https://goproxy.cn,direct";
    private static final String DEFAULT_GO_SUMDB = "sum.golang.google.cn";
    private static final int SWE_BENCH_PRO_AGENT_MAX_STEPS = 20;
    private static final int MODEL_IO_MAX_RAW_RESPONSES_PER_ATTEMPT = 80;
    private static final int MODEL_IO_MAX_MODEL_INPUT_BLOCKS_PER_ATTEMPT = 30;
    private static final int MODEL_IO_MAX_TEXT_CHARS = 240_000;
    private static final String DOCKERIGNORE_TEXT = String.join("\n",
            ".git",
            "**/.git",
            "**/.hg",
            "**/.svn",
            "repo/**/node_modules",
            "repo/**/.venv",
            "repo/**/venv",
            "repo/**/env",
            "repo/**/__pycache__",
            "repo/**/.pytest_cache",
            "repo/**/.mypy_cache",
            "repo/**/.ruff_cache",
            "repo/**/.tox",
            "repo/**/.nox",
            "repo/**/.gradle",
            "repo/**/target",
            "repo/**/.cargo",
            "logs/**",
            "model_evaluation/",
            "docker-image/**",
            "*.tar",
            "*.tar.gz",
            "*.sha256",
            ".DS_Store",
            "");
    private static final List<String> QC_PLACEHOLDER_NEEDLES = List.of(
            "PENDING_",
            "待审校",
            "待补充",
            "待评测",
            "待验证",
            "待三位 reviewer 完成后填写",
            "完成真实性与问题陈述核对后填写",
            "完成 baseline/fixed/pass-to-pass 与过拟合复核后填写",
            "完成 Docker、模型评测和交付清洁度核对后填写"
    );

    private final SweTaskMapper taskMapper;
    private final SwePipelineRunMapper runMapper;
    private final SwePipelineStageMapper stageMapper;
    private final SweArtifactMapper artifactMapper;
    private final SweCandidateMapper candidateMapper;
    private final SweProperties properties;
    private final SweCommandRunner commandRunner;
    private final SweAcceptanceReportService acceptanceReportService;

    /**
     * Creates a SWE-Pro task. When samplePath points to an existing package,
     * missing metadata is hydrated from task.json.
     */
    @Transactional(rollbackFor = Exception.class)
    public SweTaskDTO createTask(SweTaskCreateRequest request) {
        hydrateFromTaskJson(request);
        SweCandidateEntity candidate = resolveCandidate(request.getCandidateId(), request.getSourceUrl());
        if (candidate != null
                && "DELIVERED".equals(candidate.getDuplicateStatus())
                && !StringUtils.hasText(request.getSamplePath())) {
            throw new BusinessException("该候选 PR 已标记为已交付，不能重复创建任务");
        }
        hydrateFromCandidate(request, candidate);
        if (!StringUtils.hasText(request.getRepo())) {
            throw new BusinessException("repo不能为空，或提供包含task.json的samplePath");
        }

        SweTaskEntity entity = new SweTaskEntity();
        BeanUtils.copyProperties(request, entity);
        if (candidate != null) {
            entity.setCandidateId(candidate.getId());
        }
        entity.setStatus(SwePipelineStatus.CREATED.getCode());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        taskMapper.insert(entity);
        markCandidateStatus(entity, "selected");
        return toTaskDTO(entity, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public SweTaskDTO createTaskFromCandidate(SweTaskFromCandidateRequest request) {
        SweCandidateEntity candidate = requireCandidate(request.getCandidateId());
        if ("DELIVERED".equals(candidate.getDuplicateStatus())) {
            throw new BusinessException("该候选 PR 已标记为已交付，不能重复创建任务");
        }

        SweTaskEntity entity = new SweTaskEntity();
        entity.setTaskName(StringUtils.hasText(request.getTaskName())
                ? request.getTaskName()
                : defaultTaskName(candidate));
        entity.setCandidateId(candidate.getId());
        entity.setRepo(candidate.getRepo());
        entity.setSourceUrl(candidate.getPrUrl());
        entity.setBaseCommit(candidate.getBaseCommit());
        entity.setFixCommit(candidate.getFixCommit());
        entity.setRepoLanguage(candidate.getPrimaryLanguage());
        entity.setIssueSpecificity("[]");
        entity.setIssueCategories("[]");
        entity.setSamplePath(null);
        entity.setStatus(SwePipelineStatus.CREATED.getCode());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        taskMapper.insert(entity);

        markCandidateStatus(candidate.getId(), "selected");
        return toTaskDTO(entity, false);
    }

    public SweTaskDTO getTask(Long id) {
        SweTaskEntity task = requireTask(id);
        return toTaskDTO(task, true);
    }

    public List<SweTaskDTO> listTasks() {
        List<SweTaskEntity> entities = taskMapper.selectList(
                new LambdaQueryWrapper<SweTaskEntity>().orderByDesc(SweTaskEntity::getCreatedAt));
        return entities.stream()
                .map(entity -> toTaskDTO(entity, false))
                .toList();
    }

    /**
     * Starts an async pipeline run and returns immediately with initialized stages.
     */
    @Transactional(rollbackFor = Exception.class)
    public SwePipelineRunDTO startRun(SwePipelineStartRequest request) {
        if (request.getResumeRunId() != null) {
            return resumeRun(request);
        }
        SweTaskEntity task = requireTask(request.getTaskId());
        attachCandidate(task);
        String samplePath = StringUtils.hasText(request.getSamplePath())
                ? request.getSamplePath()
                : (isGithubPullTask(task) ? null : task.getSamplePath());

        SwePipelineRunEntity run = new SwePipelineRunEntity();
        run.setTaskId(task.getId());
        run.setCandidateId(task.getCandidateId());
        run.setStatus(SwePipelineStatus.CREATED.getCode());
        run.setCurrentStage(SwePipelineStage.ENVIRONMENT_CHECK.getCode());
        run.setWorkspacePath(request.getWorkspacePath());
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        for (SwePipelineStage stage : SwePipelineStage.values()) {
            stageMapper.insert(newStage(run.getId(), stage));
        }

        launchAfterCommit(task, run.getId(), samplePath, false);

        return getRun(run.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public SwePipelineRunDTO resumeRun(SwePipelineStartRequest request) {
        SwePipelineRunEntity run = runMapper.selectById(request.getResumeRunId());
        if (run == null) {
            throw new BusinessException("SWE-Pro pipeline run not found");
        }
        if (SwePipelineStatus.RUNNING.getCode().equals(run.getStatus())) {
            throw new BusinessException("该流水线正在运行，不能重复续跑");
        }
        if (SwePipelineStatus.COMPLETED.getCode().equals(run.getStatus())) {
            throw new BusinessException("该流水线已完成，无需断点续跑");
        }
        SweTaskEntity task = requireTask(run.getTaskId());
        if (request.getTaskId() != null && !request.getTaskId().equals(task.getId())) {
            throw new BusinessException("resumeRunId 与 taskId 不匹配");
        }
        SwePipelineStage resumeFromStage = resolveResumeFromStage(request.getResumeFromStage());
        attachCandidate(task);
        if (run.getCandidateId() == null && task.getCandidateId() != null) {
            run.setCandidateId(task.getCandidateId());
        }
        run.setStatus(SwePipelineStatus.CREATED.getCode());
        if (resumeFromStage != null) {
            run.setCurrentStage(resumeFromStage.getCode());
        }
        run.setFinishedAt(null);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        clearRunFinishedAt(run.getId());
        clearRunErrorMessage(run.getId());
        if (resumeFromStage != null) {
            resetStagesFrom(run.getId(), resumeFromStage);
        }

        String samplePath = isGithubPullTask(task)
                ? null
                : (StringUtils.hasText(request.getSamplePath()) ? request.getSamplePath() : task.getSamplePath());
        launchAfterCommit(task, run.getId(), samplePath, true);
        return getRun(run.getId());
    }

    private SwePipelineStage resolveResumeFromStage(String stageCode) {
        if (!StringUtils.hasText(stageCode)) {
            return null;
        }
        SwePipelineStage stage = SwePipelineStage.fromCode(stageCode.trim());
        if (stage == null) {
            throw new BusinessException("未知的续跑阶段: " + stageCode);
        }
        return stage;
    }

    private void resetStagesFrom(Long runId, SwePipelineStage fromStage) {
        stageMapper.update(null, new LambdaUpdateWrapper<SwePipelineStageEntity>()
                .set(SwePipelineStageEntity::getStatus, SwePipelineStatus.CREATED.getCode())
                .set(SwePipelineStageEntity::getResultSummary, null)
                .set(SwePipelineStageEntity::getErrorMessage, null)
                .set(SwePipelineStageEntity::getStartedAt, null)
                .set(SwePipelineStageEntity::getFinishedAt, null)
                .set(SwePipelineStageEntity::getUpdatedAt, LocalDateTime.now())
                .eq(SwePipelineStageEntity::getRunId, runId)
                .ge(SwePipelineStageEntity::getSortOrder, fromStage.getSortOrder()));
    }

    private void launchAfterCommit(SweTaskEntity task, Long runId, String samplePath, boolean resumeMode) {
        Runnable action = () -> CompletableFuture.runAsync(() -> executeRun(task, runId, samplePath, resumeMode))
                .exceptionally(error -> {
                    log.error("SWE-Pro pipeline async execution failed, runId={}", runId, error);
                    failRun(runId, error.getMessage());
                    return null;
                });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public SwePipelineRunDTO getRun(Long runId) {
        SwePipelineRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("SWE-Pro pipeline run not found");
        }
        return toRunDTO(run, true);
    }

    public SweModelIoConsoleDTO getModelIoConsole(Long runId) {
        SwePipelineRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("SWE-Pro pipeline run not found");
        }
        SweTaskEntity task = taskMapper.selectById(run.getTaskId());
        if (task == null || !StringUtils.hasText(task.getSamplePath())) {
            throw new BusinessException("SWE-Pro task samplePath missing; model I/O console is unavailable");
        }
        Path packagePath = Path.of(task.getSamplePath()).toAbsolutePath().normalize();
        SweModelIoConsoleDTO console = new SweModelIoConsoleDTO();
        console.setRunId(runId);
        console.setTaskId(run.getTaskId());
        console.setPackagePath(packagePath.toString());
        Path problemStatement = packagePath.resolve("model_evaluation/_swe_agent_problem_statement.md");
        Path guardConfig = packagePath.resolve("model_evaluation/_swe_agent_guard.yaml");
        console.setProblemStatementPath(problemStatement.toString());
        console.setProblemStatement(readTextIfRegular(problemStatement));
        console.setGuardConfigPath(guardConfig.toString());
        console.setGuardConfig(readTextIfRegular(guardConfig));
        console.setAttempts(readModelIoAttempts(packagePath));
        return console;
    }

    public List<SwePipelineRunDTO> listRuns(Long taskId) {
        LambdaQueryWrapper<SwePipelineRunEntity> wrapper = new LambdaQueryWrapper<SwePipelineRunEntity>()
                .orderByDesc(SwePipelineRunEntity::getCreatedAt);
        if (taskId != null) {
            wrapper.eq(SwePipelineRunEntity::getTaskId, taskId);
        }
        return runMapper.selectList(wrapper).stream()
                .map(run -> toRunDTO(run, false))
                .toList();
    }

    private void executeRun(SweTaskEntity task, Long runId, String samplePathText, boolean resumeMode) {
        markRunRunning(runId);
        markTaskStatus(task.getId(), SwePipelineStatus.RUNNING.getCode());
        markCandidateStatus(task, "running");
        try {
            runStage(runId, SwePipelineStage.ENVIRONMENT_CHECK, () -> checkEnvironment(runId), resumeMode);
            Path samplePath;
            if (StringUtils.hasText(samplePathText)) {
                samplePath = requireSamplePath(samplePathText);
                executeExistingPackageRun(task, runId, samplePath, resumeMode);
            } else {
                samplePath = executeCandidateProductionRun(task, runId, resumeMode);
            }
            updateTaskSamplePath(task.getId(), samplePath);
            completeRun(runId);
            markTaskStatus(task.getId(), SwePipelineStatus.COMPLETED.getCode());
        } catch (Exception e) {
            log.error("SWE-Pro pipeline failed, runId={}", runId, e);
            failRun(runId, e.getMessage());
            markTaskStatus(task.getId(), SwePipelineStatus.FAILED.getCode());
            markCandidateStatus(task, "failed");
        }
    }

    private boolean isGithubPullTask(SweTaskEntity task) {
        return task != null
                && StringUtils.hasText(task.getSourceUrl())
                && task.getSourceUrl().contains("github.com/")
                && task.getSourceUrl().contains("/pull/");
    }

    private void executeExistingPackageRun(SweTaskEntity task, Long runId, Path samplePath, boolean resumeMode) {
        runStageUnchecked(runId, SwePipelineStage.SOURCE_INGEST, () -> inspectSource(task, runId, samplePath), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.CANDIDATE_DEDUP_REGISTER, () -> "Existing package mode: candidate registry skipped", resumeMode);
        runStageUnchecked(runId, SwePipelineStage.TASK_PACKAGE_INIT, () -> "Existing package mode: package already initialized at " + samplePath, resumeMode);
        runStageUnchecked(runId, SwePipelineStage.PATCH_VERIFY, () -> inspectPatches(runId, samplePath), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.HARNESS_BUILD, () -> inspectHarness(runId, samplePath), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.LOCAL_VERIFY, () -> inspectVerificationLogs(runId, samplePath), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.MODEL_QWEN_EVAL, () -> inspectModelSummary(runId, samplePath, "qwen"), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.MODEL_OPUS_EVAL, () -> inspectModelSummary(runId, samplePath, "opus"), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.DOCKER_PACKAGE, () -> inspectDockerEvidence(runId, samplePath), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.QC_REVIEW, () -> inspectQualityEvidence(runId, samplePath), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.PACKAGE_EXPORT, () -> inspectPackageExport(runId, samplePath), resumeMode);
    }

    private Path executeCandidateProductionRun(SweTaskEntity task, Long runId, boolean resumeMode) {
        Path runWorkspace = runWorkspace(runId);
        Path[] packagePath = new Path[1];
        if (resumeMode && StringUtils.hasText(task.getSamplePath())) {
            packagePath[0] = Path.of(task.getSamplePath());
        }
        runStageUnchecked(runId, SwePipelineStage.SOURCE_INGEST, () -> inspectCandidateSource(task), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.CANDIDATE_DEDUP_REGISTER, () -> dedupAndRegisterCandidate(task), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.TASK_PACKAGE_INIT, () -> {
            packagePath[0] = initializeTaskPackage(task, runId, runWorkspace);
            updateTaskSamplePath(task.getId(), packagePath[0]);
            return "Task package initialized at " + packagePath[0];
        }, resumeMode);
        runStageUnchecked(runId, SwePipelineStage.PATCH_VERIFY, () -> verifyPatchApplication(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.HARNESS_BUILD, () -> inspectHarness(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.LOCAL_VERIFY, () -> runLocalVerification(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.MODEL_QWEN_EVAL, () -> runQwenEvaluation(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.MODEL_OPUS_EVAL, () -> runOpusEvaluation(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.DOCKER_PACKAGE, () -> runDockerPackage(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.QC_REVIEW, () -> inspectQualityEvidence(runId, requireInitializedPackage(packagePath)), resumeMode);
        runStageUnchecked(runId, SwePipelineStage.PACKAGE_EXPORT, () -> runPackageExport(runId, requireInitializedPackage(packagePath)), resumeMode);
        return requireInitializedPackage(packagePath);
    }

    private void runStage(Long runId, SwePipelineStage stage, StageAction action) throws Exception {
        runStage(runId, stage, action, false);
    }

    private void runStage(Long runId, SwePipelineStage stage, StageAction action, boolean resumeMode) throws Exception {
        SwePipelineStageEntity stageEntity = getStage(runId, stage);
        if (resumeMode && SwePipelineStatus.COMPLETED.getCode().equals(stageEntity.getStatus())) {
            updateCurrentStage(runId, stage.getCode());
            stageEntity.setErrorMessage(null);
            clearStageErrorMessage(stageEntity.getId());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        stageEntity.setStatus(SwePipelineStatus.RUNNING.getCode());
        stageEntity.setErrorMessage(null);
        stageEntity.setStartedAt(now);
        stageEntity.setFinishedAt(null);
        stageEntity.setUpdatedAt(now);
        stageMapper.updateById(stageEntity);
        clearStageFinishedAt(stageEntity.getId());
        clearStageErrorMessage(stageEntity.getId());
        updateCurrentStage(runId, stage.getCode());

        try {
            String summary = action.execute();
            stageEntity.setStatus(SwePipelineStatus.COMPLETED.getCode());
            stageEntity.setResultSummary(summary);
            stageEntity.setErrorMessage(null);
            clearStageErrorMessage(stageEntity.getId());
        } catch (Exception e) {
            stageEntity.setStatus(SwePipelineStatus.FAILED.getCode());
            stageEntity.setErrorMessage(e.getMessage());
            throw e;
        } finally {
            stageEntity.setFinishedAt(LocalDateTime.now());
            stageEntity.setUpdatedAt(LocalDateTime.now());
            stageMapper.updateById(stageEntity);
        }
    }

    private void runStageUnchecked(Long runId, SwePipelineStage stage, StageAction action) {
        runStageUnchecked(runId, stage, action, false);
    }

    private void runStageUnchecked(Long runId, SwePipelineStage stage, StageAction action, boolean resumeMode) {
        try {
            runStage(runId, stage, action, resumeMode);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("stage failed: " + stage.getCode(), e);
        }
    }

    private String inspectSource(SweTaskEntity task, Long runId, Path samplePath) {
        Path taskJson = requireFile(samplePath.resolve("task.json"), "task.json is required");
        Path problemStatement = requireFile(samplePath.resolve("problem_statement.md"), "problem_statement.md is required");
        recordArtifact(runId, "METADATA", taskJson);
        recordArtifact(runId, "PROBLEM_STATEMENT", problemStatement);

        JSONObject json = readJson(taskJson);
        String taskId = json.getString("task_id");
        String repo = json.getString("repo");
        if (StringUtils.hasText(task.getRepo()) && StringUtils.hasText(repo) && !task.getRepo().equals(repo)) {
            throw new BusinessException("task repo does not match task.json repo");
        }
        return "Source package accepted, taskId=" + taskId + ", repo=" + repo;
    }

    private String inspectRepo(Long runId, Path samplePath) throws IOException {
        Path repoPath = requireDirectory(samplePath.resolve("repo"), "repo directory is required");
        recordArtifact(runId, "REPO", repoPath);
        long fileCount;
        try (Stream<Path> stream = Files.walk(repoPath)) {
            fileCount = stream.filter(Files::isRegularFile).count();
        }
        return "Repository snapshot found, files=" + fileCount;
    }

    private String inspectPatches(Long runId, Path samplePath) throws IOException {
        Path goldPatch = requireFile(samplePath.resolve("patches/gold.patch"), "gold.patch is required");
        Path testPatch = requireFile(samplePath.resolve("patches/test.patch"), "test.patch is required");
        recordArtifact(runId, "GOLD_PATCH", goldPatch);
        recordArtifact(runId, "TEST_PATCH", testPatch);
        return "Patch files found, goldLines=" + countLines(goldPatch) + ", testLines=" + countLines(testPatch);
    }

    private String inspectHarness(Long runId, Path samplePath) {
        List<Path> required = List.of(
                samplePath.resolve("scripts/verify_patch_application.sh"),
                samplePath.resolve("scripts/run_selected_tests.sh"),
                samplePath.resolve("scripts/parser.py"),
                samplePath.resolve("dockerfiles/Dockerfile")
        );
        required.forEach(path -> {
            requireFile(path, path.getFileName() + " is required");
            recordArtifact(runId, "HARNESS", path);
        });
        String runtimeSummary = resolveRuntimeEnvironment(runId, samplePath);
        return "Harness scripts, Dockerfile, and runtime environment are present; " + runtimeSummary;
    }

    private String resolveRuntimeEnvironment(Long runId, Path packagePath) {
        SweCommandRunner.CommandResult result = commandRunner.run(
                "resolve_runtime_env",
                List.of(
                        properties.getPython(),
                        toolkitScript("resolve_runtime_env.py").toString(),
                        packagePath.toString(),
                        "--update-task-metadata"
                ),
                packagePath,
                runLogDir(runId),
                githubEnv(),
                Duration.ofMinutes(3),
                false);
        recordArtifact(runId, "RUNTIME_ENV_LOG", result.getLogPath());
        Path runtimeEnv = requireFile(packagePath.resolve("runtime_env.json"), "runtime_env.json is required");
        recordArtifact(runId, "RUNTIME_ENV", runtimeEnv);
        JSONObject json = readJson(runtimeEnv);
        JSONArray languages = json.getJSONArray("languages");
        JSONArray setupCommands = json.getJSONArray("setup_commands");
        return "languages=" + (languages == null ? 0 : languages.size())
                + ", setupCommands=" + (setupCommands == null ? 0 : setupCommands.size());
    }

    private String inspectVerificationLogs(Long runId, Path samplePath) {
        Path verification = requireFile(samplePath.resolve("verification.md"), "verification.md is required");
        recordArtifact(runId, "VERIFY_LOG", verification);
        List<Path> logs = findMatchingFiles(samplePath.resolve("logs"), ".log", ".txt", ".md");
        if (logs.isEmpty()) {
            throw new BusinessException("verification logs are required under logs/");
        }
        logs.forEach(path -> recordArtifact(runId, "VERIFY_LOG", path));
        return "Verification evidence found, logFiles=" + logs.size();
    }

    private String inspectQualityEvidence(Long runId, Path samplePath) {
        acceptanceReportService.ensureReport(samplePath);
        List<Path> required = List.of(
                samplePath.resolve("乙方质检-SWE-Pro数据验收标准对照表.xlsx"),
                samplePath.resolve("review/reviewer_1.md"),
                samplePath.resolve("review/reviewer_2.md"),
                samplePath.resolve("review/reviewer_3.md"),
                samplePath.resolve("review/adjudication_and_calibration.md")
        );
        required.forEach(path -> {
            requireFile(path, path.getFileName() + " is required");
            validateQualityEvidence(path);
            recordArtifact(runId, "QC_EVIDENCE", path);
        });
        return "QC checklist and triple-review evidence are present";
    }

    private void validateQualityEvidence(Path path) {
        if (!path.getFileName().toString().endsWith(".md")) {
            return;
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("failed to read QC evidence: " + path, e);
        }
        for (String needle : QC_PLACEHOLDER_NEEDLES) {
            if (text.contains(needle)) {
                throw new BusinessException("QC evidence contains unresolved placeholder: "
                        + path.getFileName() + " -> " + needle);
            }
        }
    }

    private String inspectPackageExport(Long runId, Path samplePath) {
        List<Path> optional = new ArrayList<>();
        optional.add(packageArchivePath(samplePath));
        optional.add(packageArchiveChecksumPath(samplePath));
        optional.addAll(findMatchingFiles(samplePath.resolve("docker-image"), ".tar", ".sha256"));
        optional.addAll(findMatchingFiles(samplePath.resolve("model_evaluation"), "summary.json"));
        optional.addAll(findDeliveryFiles(samplePath));

        int found = 0;
        for (Path path : optional) {
            if (Files.exists(path)) {
                found++;
                recordArtifact(runId, "PACKAGE_EXPORT", path);
            }
        }
        return "Package export evidence indexed, optionalArtifactsFound=" + found;
    }

    private String checkEnvironment(Long runId) {
        Path toolkitRoot = requireDirectory(toolkitRoot(), "SWE-Pro toolkit root does not exist");
        requireFile(toolkitRoot.resolve("scripts/prepare_tasks_from_candidates.py"), "prepare_tasks_from_candidates.py is required");
        requireFile(toolkitRoot.resolve("scripts/resolve_runtime_env.py"), "resolve_runtime_env.py is required");
        requireFile(toolkitRoot.resolve("scripts/eval_with_swe_agent.py"), "eval_with_swe_agent.py is required");
        requireFile(toolkitRoot.resolve("scripts/package_task.py"), "package_task.py is required");
        requireDirectory(sweAgentRoot(), "SWE-agent root does not exist");
        runEnvironmentProbe(runId, "git_version", List.of("git", "--version"));
        runEnvironmentProbe(runId, "python_version", List.of(properties.getPython(), "--version"));
        runEnvironmentProbe(runId, "docker_info", List.of("docker", "info", "--format", "{{.ServerVersion}}"));
        ensureModelConfig("qwen", properties.getQwen());
        ensureModelConfig("opus", properties.getOpus());
        return "Environment ready: git/python/docker/toolkit/model configs are available";
    }

    private void runEnvironmentProbe(Long runId, String name, List<String> command) {
        SweCommandRunner.CommandResult result = commandRunner.run(
                name,
                command,
                Path.of(".").toAbsolutePath().normalize(),
                runLogDir(runId),
                Map.of(),
                Duration.ofSeconds(30),
                false);
        recordArtifact(runId, "ENV_LOG", result.getLogPath());
    }

    private String inspectCandidateSource(SweTaskEntity task) {
        if (!StringUtils.hasText(task.getRepo()) || !StringUtils.hasText(task.getSourceUrl())) {
            throw new BusinessException("候选任务必须包含 repo 和 sourceUrl");
        }
        if (!StringUtils.hasText(task.getBaseCommit()) || !StringUtils.hasText(task.getFixCommit())) {
            throw new BusinessException("候选任务必须包含 baseCommit 和 fixCommit");
        }
        return "Candidate accepted, repo=" + task.getRepo() + ", pr=" + task.getSourceUrl();
    }

    private String dedupAndRegisterCandidate(SweTaskEntity task) {
        if (isDelivered(task)) {
            throw new BusinessException("该候选 PR 已交付，已按当前规则过滤");
        }
        SweCandidateEntity candidate = resolveCandidateForTask(task);
        String benchmarkStatus = candidate == null || !StringUtils.hasText(candidate.getBenchmarkStatus())
                ? "UNKNOWN"
                : candidate.getBenchmarkStatus();
        String failedHistoryStatus = candidate == null || !StringUtils.hasText(candidate.getFailedHistoryStatus())
                ? "UNKNOWN"
                : candidate.getFailedHistoryStatus();
        return "Dedup status: delivered=false, benchmark=" + benchmarkStatus
                + ", failedHistory=" + failedHistoryStatus;
    }

    private Path initializeTaskPackage(SweTaskEntity task, Long runId, Path runWorkspace) throws IOException {
        Path productionRoot = productionRoot();
        Files.createDirectories(runWorkspace);
        Files.createDirectories(productionRoot);
        Path csv = runWorkspace.resolve("candidate.csv");
        Files.writeString(csv, candidateCsv(task), StandardCharsets.UTF_8);
        recordArtifact(runId, "CANDIDATE_CSV", csv);

        List<String> command = new ArrayList<>(List.of(
                properties.getPython(),
                toolkitScript("prepare_tasks_from_candidates.py").toString(),
                "--candidates", csv.toString(),
                "--out-root", productionRoot.toString(),
                "--min-score", "0",
                "--statuses", "scored,selected,new",
                "--limit", "1",
                "--only", task.getSourceUrl()
        ));
        SweCommandRunner.CommandResult result = runPrepareTaskPackageWithRetry(runId, command);
        recordArtifact(runId, "PIPELINE_LOG", result.getLogPath());
        Path packagePath = productionRoot.resolve(packageName(task));
        Path preparedPackage = requireDirectory(packagePath, "prepared package directory not found");
        normalizeGeneratedPackage(preparedPackage);
        return preparedPackage;
    }

    private SweCommandRunner.CommandResult runPrepareTaskPackageWithRetry(Long runId, List<String> command) {
        BusinessException lastError = null;
        for (int attempt = 1; attempt <= PACKAGE_PREPARE_MAX_ATTEMPTS; attempt++) {
            try {
                return commandRunner.run(
                        "prepare_task_package_attempt_" + attempt,
                        command,
                        toolkitRoot(),
                        runLogDir(runId),
                        githubEnv(),
                        Duration.ofMinutes(5),
                        false);
            } catch (BusinessException error) {
                lastError = error;
                if (attempt >= PACKAGE_PREPARE_MAX_ATTEMPTS) {
                    break;
                }
                log.warn("prepare_task_package failed, retrying, runId={}, attempt={}/{}",
                        runId, attempt, PACKAGE_PREPARE_MAX_ATTEMPTS, error);
                sleepBeforeRetry();
            }
        }
        throw lastError == null ? new BusinessException("prepare_task_package failed") : lastError;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Duration.ofSeconds(3).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("prepare_task_package retry interrupted", e);
        }
    }

    private String verifyPatchApplication(Long runId, Path packagePath) {
        SweCommandRunner.CommandResult result = commandRunner.run(
                "verify_patch_application",
                List.of("bash", "scripts/verify_patch_application.sh"),
                packagePath,
                runLogDir(runId),
                Map.of(),
                Duration.ofMinutes(10),
                false);
        recordArtifact(runId, "VERIFY_LOG", result.getLogPath());
        try {
            inspectPatches(runId, packagePath);
        } catch (IOException e) {
            throw new BusinessException("failed to inspect patches", e);
        }
        return "Patch application verified";
    }

    private String runLocalVerification(Long runId, Path packagePath) {
        TaskSpecSnapshot snapshot = taskSpecSnapshot(packagePath);
        SweCommandRunner.CommandResult result = commandRunner.run(
                "docker_verify",
                List.of(
                        properties.getPython(),
                        toolkitScript("package_task.py").toString(),
                        packagePath.toString(),
                        "--docker"
                ),
                packagePath.getParent(),
                runLogDir(runId),
                Map.of(),
                null,
                false);
        recordArtifact(runId, "LOCAL_VERIFY_LOG", result.getLogPath());
        assertTaskSpecUnchanged(packagePath, snapshot, "docker verification");
        findMatchingFiles(packagePath.resolve("logs/docker"), ".log")
                .forEach(path -> recordArtifact(runId, "LOCAL_VERIFY_LOG", path));
        return "Docker verification passed: baseline failed as expected, fixed/pass-to-pass passed";
    }

    private void normalizeGeneratedPackage(Path packagePath) {
        normalizeDockerignore(packagePath);
        normalizeVerificationScripts(packagePath);
        cleanupDeliveryTempFiles(packagePath);
    }

    private void normalizeDockerignore(Path packagePath) {
        Path dockerignore = packagePath.resolve(".dockerignore");
        try {
            if (!Files.isRegularFile(dockerignore)
                    || !DOCKERIGNORE_TEXT.equals(Files.readString(dockerignore, StandardCharsets.UTF_8))) {
                Files.writeString(dockerignore, DOCKERIGNORE_TEXT, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BusinessException("failed to normalize .dockerignore", e);
        }
    }

    private void normalizeVerificationScripts(Path packagePath) {
        normalizeGitBaselineScript(packagePath.resolve("scripts/run_selected_tests.sh"));
        normalizeGitBaselineScript(packagePath.resolve("scripts/verify_patch_application.sh"));
    }

    private void normalizeGitBaselineScript(Path scriptPath) {
        if (!Files.isRegularFile(scriptPath)) {
            return;
        }
        try {
            String text = Files.readString(scriptPath, StandardCharsets.UTF_8);
            if (text.contains("baseline snapshot")) {
                return;
            }
            String needle = "cd \"$ROOT/repo\"\n";
            String insert = needle
                    + "if [ ! -d .git ]; then\n"
                    + "  git init -q\n"
                    + "  git config user.email \"fly-agent@example.invalid\"\n"
                    + "  git config user.name \"fly-agent\"\n"
                    + "  git add -A\n"
                    + "  git commit -q -m \"baseline snapshot\"\n"
                    + "fi\n";
            if (text.contains(needle)) {
                Files.writeString(scriptPath, text.replace(needle, insert), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BusinessException("failed to normalize verification script: " + scriptPath, e);
        }
    }

    private void cleanupDeliveryTempFiles(Path packagePath) {
        if (!Files.isDirectory(packagePath)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(packagePath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> ".DS_Store".equals(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new BusinessException("failed to delete temp file: " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new BusinessException("failed to clean delivery temp files", e);
        }
    }

    private String runQwenEvaluation(Long runId, Path packagePath) {
        Path reusableSummary = findLatestReusableModelSummary(packagePath, "qwen");
        if (reusableSummary != null) {
            recordArtifact(runId, "MODEL_SUMMARY", reusableSummary);
            JSONObject summary = readJson(reusableSummary);
            double passRate = summary.getDoubleValue("pass_rate");
            if (passRate > 0.5d) {
                throw new BusinessException("Qwen pass rate@4 超过 50%，任务过简单，停止进入 Opus 阶段");
            }
            return "Qwen evaluation reused existing gate evidence: passes="
                    + summary.getIntValue("passes")
                    + "/" + summary.getIntValue("attempts")
                    + ", passRate=" + passRate
                    + modelStatusCountsSuffix(summary);
        }
        JSONObject summary = runModelEvaluation(runId, packagePath, "qwen3.6_flash_pass4_swebench_agentic", properties.getQwen(),
                properties.getQwenAttempts(), "QWEN_API_KEY", false, true);
        ensureModelEvaluationInfrastructureReady("Qwen", summary);
        double passRate = summary.getDoubleValue("pass_rate");
        if (passRate > 0.5d) {
            throw new BusinessException("Qwen pass rate@4 超过 50%，任务过简单，停止进入 Opus 阶段");
        }
        return "Qwen evaluation passed gate: passes=" + summary.getIntValue("passes")
                + "/" + summary.getIntValue("attempts")
                + ", passRate=" + passRate
                + modelStatusCountsSuffix(summary);
    }

    private String runOpusEvaluation(Long runId, Path packagePath) {
        Path reusableSummary = findLatestReusableModelSummary(packagePath, "opus");
        if (reusableSummary != null) {
            recordArtifact(runId, "MODEL_SUMMARY", reusableSummary);
            JSONObject summary = readJson(reusableSummary);
            return "Opus evaluation reused existing passing evidence: passes="
                    + summary.getIntValue("passes")
                    + "/" + summary.getIntValue("attempts");
        }
        JSONObject summary = runModelEvaluation(runId, packagePath, "opus4.7_pass8_swebench_agentic", properties.getOpus(),
                positiveAttempts(properties.getOpusAttempts(), 8), "OPUS_API_KEY", true, true);
        ensureModelEvaluationInfrastructureReady("Opus", summary);
        if (summary.getIntValue("passes") <= 0) {
            throw new BusinessException("Opus pass@8 为 0，任务不满足验收难度" + modelStatusCountsSuffix(summary));
        }
        return "Opus evaluation passed gate: passes=" + summary.getIntValue("passes")
                + "/" + summary.getIntValue("attempts")
                + modelStatusCountsSuffix(summary);
    }

    private int positiveAttempts(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int positiveInteger(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private JSONObject runModelEvaluation(
            Long runId,
            Path packagePath,
            String outName,
            SweProperties.Model model,
            Integer attempts,
            String apiKeyEnv,
            boolean allowFailure,
            boolean requireSummary) {
        ensureModelConfig(outName, model);
        TaskSpecSnapshot snapshot = taskSpecSnapshot(packagePath);
        List<String> command = new ArrayList<>();
        command.add(properties.getPython());
        command.add(toolkitScript("eval_with_swe_agent.py").toString());
        command.add(packagePath.toString());
        command.add("--swe-agent-root");
        command.add(sweAgentRoot().toString());
        command.add("--model");
        command.add(model.getModel());
        command.add("--base-url");
        command.add(modelBaseUrl(model));
        command.add("--api-key-env");
        command.add(apiKeyEnv);
        command.add("--attempts");
        command.add(String.valueOf(attempts));
        command.add("--out-name");
        command.add(outName);
        command.add("--timeout");
        command.add(String.valueOf(properties.getModelTimeoutSeconds()));
        command.add("--max-tokens");
        command.add(String.valueOf(positiveInteger(model.getMaxTokens(), 4096)));
        command.add("--max-input-tokens");
        command.add(String.valueOf(positiveInteger(model.getMaxInputTokens(), 22000)));
        command.add("--temperature");
        command.add(String.valueOf(model.getTemperature() == null ? 0.7d : model.getTemperature()));
        command.add("--provider");
        command.add(StringUtils.hasText(model.getProvider()) ? model.getProvider() : "openai");
        command.add("--agent-max-steps");
        command.add(String.valueOf(resolveSweAgentMaxSteps()));
        if (outName.toLowerCase().contains("opus") && StringUtils.hasText(properties.getOpusMaxStepsSchedule())) {
            command.add("--agent-max-steps-schedule");
            command.add(properties.getOpusMaxStepsSchedule());
        }
        command.add("--enable-thinking");
        command.add(outName.toLowerCase().contains("qwen") ? "false" : "omit");
        Map<String, String> env = new HashMap<>(verificationEnv());
        env.put(apiKeyEnv, model.getToken());
        SweCommandRunner.CommandResult result = commandRunner.run(
                "model_eval_" + outName,
                command,
                packagePath,
                runLogDir(runId),
                env,
                null,
                allowFailure);
        recordArtifact(runId, "MODEL_EVAL_LOG", result.getLogPath());
        assertTaskSpecUnchanged(packagePath, snapshot, "model evaluation " + outName);
        Path summaryPath = findLatestSummary(packagePath, outName);
        if (summaryPath == null || !Files.isRegularFile(summaryPath)) {
            if (requireSummary) {
                throw new BusinessException("model evaluation summary missing: " + outName);
            }
            return new JSONObject();
        }
        recordArtifact(runId, "MODEL_SUMMARY", summaryPath);
        JSONObject summary = readJson(summaryPath);
        return summary;
    }

    private Path sweAgentRoot() {
        String root = properties.getSweAgent() == null ? null : properties.getSweAgent().getRoot();
        if (!StringUtils.hasText(root)) {
            root = "tools/SWE-agent";
        }
        Path path = Path.of(root);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(path);
        }
        return path.normalize();
    }

    private int resolveSweAgentMaxSteps() {
        if (properties.getSweAgent() == null) {
            return SWE_BENCH_PRO_AGENT_MAX_STEPS;
        }
        return positiveInteger(properties.getSweAgent().getMaxSteps(), SWE_BENCH_PRO_AGENT_MAX_STEPS);
    }

    private String runDockerPackage(Long runId, Path packagePath) {
        TaskSpecSnapshot snapshot = taskSpecSnapshot(packagePath);
        cleanupDeliveryTempFiles(packagePath);
        SweCommandRunner.CommandResult result = commandRunner.run(
                "docker_package",
                List.of(
                        properties.getPython(),
                        toolkitScript("package_task.py").toString(),
                        packagePath.toString(),
                        "--docker",
                        "--save-image"
                ),
                packagePath.getParent(),
                runLogDir(runId),
                Map.of(),
                null,
                false);
        recordArtifact(runId, "DOCKER_LOG", result.getLogPath());
        assertTaskSpecUnchanged(packagePath, snapshot, "docker package");
        inspectDockerEvidence(runId, packagePath);
        return "Docker package verified and image exported";
    }

    private String runPackageExport(Long runId, Path packagePath) {
        TaskSpecSnapshot snapshot = taskSpecSnapshot(packagePath);
        cleanupDeliveryTempFiles(packagePath);
        if (hasValidPackageArchive(packagePath)) {
            assertTaskSpecUnchanged(packagePath, snapshot, "package export archive reuse");
            return inspectPackageExport(runId, packagePath);
        }
        SweCommandRunner.CommandResult result = commandRunner.run(
                "package_export",
                List.of(
                        properties.getPython(),
                        toolkitScript("package_task.py").toString(),
                        packagePath.toString(),
                        "--archive"
                ),
                packagePath.getParent(),
                runLogDir(runId),
                Map.of(),
                null,
                false);
        recordArtifact(runId, "PACKAGE_LOG", result.getLogPath());
        assertTaskSpecUnchanged(packagePath, snapshot, "package export");
        return inspectPackageExport(runId, packagePath);
    }

    private boolean hasValidPackageArchive(Path packagePath) {
        Path archive = packageArchivePath(packagePath);
        Path checksum = packageArchiveChecksumPath(packagePath);
        if (!Files.isRegularFile(archive) || !Files.isRegularFile(checksum)) {
            return false;
        }
        try {
            String expected = Files.readString(checksum, StandardCharsets.UTF_8).trim().split("\\s+")[0];
            return StringUtils.hasText(expected) && expected.equalsIgnoreCase(sha256(archive));
        } catch (Exception e) {
            log.warn("Ignoring invalid package archive checksum, archive={}", archive, e);
            return false;
        }
    }

    private Path packageArchivePath(Path packagePath) {
        return packagePath.getParent().resolve(packagePath.getFileName().toString() + ".tar.gz");
    }

    private Path packageArchiveChecksumPath(Path packagePath) {
        Path archive = packageArchivePath(packagePath);
        return archive.resolveSibling(archive.getFileName().toString() + ".sha256");
    }

    private String inspectModelSummary(Long runId, Path samplePath, String key) {
        Path summary = findLatestSummary(samplePath, key);
        if (summary == null) {
            throw new BusinessException("missing " + key + " model summary");
        }
        recordArtifact(runId, "MODEL_SUMMARY", summary);
        JSONObject json = readJson(summary);
        validateInspectedModelSummaryGate(key, json);
        return key + " summary found: passes=" + json.getIntValue("passes") + "/" + json.getIntValue("attempts")
                + modelStatusCountsSuffix(json);
    }

    private String modelStatusCountsSuffix(JSONObject summary) {
        JSONObject counts = summary == null ? null : summary.getJSONObject("status_counts");
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        List<String> nonZero = new ArrayList<>();
        for (Map.Entry<String, Object> entry : counts.entrySet()) {
            int value = entry.getValue() instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(entry.getValue()));
            if (value > 0) {
                nonZero.add(entry.getKey() + "=" + value);
            }
        }
        if (nonZero.isEmpty()) {
            return ", statusCounts=none";
        }
        return ", statusCounts=" + String.join(",", nonZero);
    }

    private void ensureModelEvaluationInfrastructureReady(String modelName, JSONObject summary) {
        int infraFailures = modelStatusCount(summary, "test_infra_failed");
        if (infraFailures > 0) {
            throw new BusinessException(modelName + " 模型评测基础设施失败，不能作为 pass@N 难度门控结果"
                    + modelStatusCountsSuffix(summary));
        }
    }

    private int modelStatusCount(JSONObject summary, String status) {
        JSONObject counts = summary == null ? null : summary.getJSONObject("status_counts");
        return counts == null ? 0 : counts.getIntValue(status);
    }

    private void validateInspectedModelSummaryGate(String key, JSONObject summary) {
        String lower = key == null ? "" : key.toLowerCase();
        ensureModelEvaluationInfrastructureReady(lower.contains("opus") ? "Opus" : "Qwen", summary);
        if (lower.contains("qwen") && summary.getDoubleValue("pass_rate") > 0.5d) {
            throw new BusinessException("Qwen pass rate@4 超过 50%，任务过简单，停止进入 Opus 阶段");
        }
        if (lower.contains("opus") && summary.getIntValue("passes") <= 0) {
            throw new BusinessException("Opus pass@8 为 0，任务不满足验收难度");
        }
    }

    private String inspectDockerEvidence(Long runId, Path samplePath) {
        List<Path> artifacts = findMatchingFiles(samplePath.resolve("docker-image"), ".tar", ".sha256");
        if (artifacts.isEmpty()) {
            throw new BusinessException("missing docker image tar or sha256");
        }
        artifacts.forEach(path -> recordArtifact(runId, "DOCKER_IMAGE", path));
        return "Docker evidence found, files=" + artifacts.size();
    }

    private void hydrateFromTaskJson(SweTaskCreateRequest request) {
        if (!StringUtils.hasText(request.getSamplePath())) {
            return;
        }
        Path taskJson = Path.of(request.getSamplePath()).resolve("task.json");
        if (!Files.isRegularFile(taskJson)) {
            return;
        }
        JSONObject json = readJson(taskJson);
        if (!StringUtils.hasText(request.getRepo())) {
            request.setRepo(json.getString("repo"));
        }
        if (!StringUtils.hasText(request.getBaseCommit())) {
            request.setBaseCommit(json.getString("base_commit"));
        }
        if (!StringUtils.hasText(request.getRepoLanguage())) {
            request.setRepoLanguage(json.getString("repo_language"));
        }
        JSONObject metadata = json.getJSONObject("metadata");
        if (metadata != null && !StringUtils.hasText(request.getFixCommit())) {
            request.setFixCommit(extractCommitFromUrl(metadata.getString("fix_commit_url")));
        }
        if (!StringUtils.hasText(request.getIssueSpecificity())) {
            request.setIssueSpecificity(String.valueOf(json.getJSONArray("issue_specificity")));
        }
        if (!StringUtils.hasText(request.getIssueCategories())) {
            request.setIssueCategories(String.valueOf(json.getJSONArray("issue_categories")));
        }
    }

    private SweCandidateEntity requireCandidate(Long id) {
        SweCandidateEntity candidate = candidateMapper.selectById(id);
        if (candidate == null) {
            throw new BusinessException("候选 PR 不存在");
        }
        return candidate;
    }

    private SweCandidateEntity resolveCandidate(Long candidateId, String sourceUrl) {
        if (candidateId != null) {
            return requireCandidate(candidateId);
        }
        if (!StringUtils.hasText(sourceUrl)) {
            return null;
        }
        return candidateMapper.selectOne(new LambdaQueryWrapper<SweCandidateEntity>()
                .eq(SweCandidateEntity::getPrUrl, sourceUrl)
                .last("LIMIT 1"));
    }

    private SweCandidateEntity resolveCandidateForTask(SweTaskEntity task) {
        if (task == null) {
            return null;
        }
        return resolveCandidate(task.getCandidateId(), task.getSourceUrl());
    }

    private void hydrateFromCandidate(SweTaskCreateRequest request, SweCandidateEntity candidate) {
        if (candidate == null) {
            return;
        }
        if (!StringUtils.hasText(request.getRepo())) {
            request.setRepo(candidate.getRepo());
        }
        if (!StringUtils.hasText(request.getSourceUrl())) {
            request.setSourceUrl(candidate.getPrUrl());
        }
        if (!StringUtils.hasText(request.getBaseCommit())) {
            request.setBaseCommit(candidate.getBaseCommit());
        }
        if (!StringUtils.hasText(request.getFixCommit())) {
            request.setFixCommit(candidate.getFixCommit());
        }
        if (!StringUtils.hasText(request.getRepoLanguage())) {
            request.setRepoLanguage(candidate.getPrimaryLanguage());
        }
    }

    private void attachCandidate(SweTaskEntity task) {
        if (task == null || task.getCandidateId() != null) {
            return;
        }
        SweCandidateEntity candidate = resolveCandidate(null, task.getSourceUrl());
        if (candidate == null) {
            return;
        }
        task.setCandidateId(candidate.getId());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private String defaultTaskName(SweCandidateEntity candidate) {
        return packageName(candidate.getRepo(), candidate.getPrNumber());
    }

    private boolean isDelivered(SweTaskEntity task) {
        SweCandidateEntity candidate = resolveCandidateForTask(task);
        if (candidate != null && "DELIVERED".equals(candidate.getDuplicateStatus())) {
            return true;
        }
        Long count = taskMapper.selectCount(new LambdaQueryWrapper<SweTaskEntity>()
                .eq(SweTaskEntity::getSourceUrl, task.getSourceUrl())
                .in(SweTaskEntity::getStatus,
                        SwePipelineStatus.COMPLETED.getCode(),
                        SwePipelineStatus.DELIVERED.getCode())
                .ne(SweTaskEntity::getId, task.getId()));
        if (count != null && count > 0) {
            return true;
        }
        return false;
    }

    private Path runWorkspace(Long runId) {
        return productionRoot().resolve(".swe-runs").resolve("run-" + runId);
    }

    private Path runLogDir(Long runId) {
        return runWorkspace(runId).resolve("logs");
    }

    private Path productionRoot() {
        return resolveLocalPath(properties.getProductionRoot(), "swe-output").toAbsolutePath().normalize();
    }

    private Path toolkitScript(String scriptName) {
        return toolkitRoot().resolve("scripts").resolve(scriptName);
    }

    private Path toolkitRoot() {
        return resolveLocalPath(properties.getToolkitRoot(), "tools/swe-pro-production");
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

    private Path requireInitializedPackage(Path[] packagePath) {
        if (packagePath[0] == null) {
            throw new BusinessException("task package is not initialized");
        }
        return packagePath[0];
    }

    private void ensureModelConfig(String label, SweProperties.Model model) {
        if (!StringUtils.hasText(model.getBaseUrl())
                || !StringUtils.hasText(model.getToken())
                || !StringUtils.hasText(model.getModel())) {
            throw new BusinessException("swe." + label + " 模型配置不完整");
        }
    }

    private String modelBaseUrl(SweProperties.Model model) {
        String baseUrl = trimTrailingSlash(model.getBaseUrl());
        baseUrl = stripChatCompletionEndpoint(baseUrl);
        String provider = StringUtils.hasText(model.getProvider()) ? model.getProvider() : "openai";
        if (!"openai".equalsIgnoreCase(provider)) {
            return baseUrl;
        }
        try {
            URI uri = new URI(baseUrl);
            String path = uri.getPath();
            if (!StringUtils.hasText(path) || "/".equals(path)) {
                return baseUrl + "/v1";
            }
        } catch (URISyntaxException ignored) {
            return baseUrl;
        }
        return baseUrl;
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String stripChatCompletionEndpoint(String value) {
        String lower = value.toLowerCase();
        String suffix = "/chat/completions";
        if (lower.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private Map<String, String> githubEnv() {
        Map<String, String> env = new HashMap<>();
        if (properties.getGithub() != null && StringUtils.hasText(properties.getGithub().getToken())) {
            env.put("GITHUB_TOKEN", properties.getGithub().getToken());
        }
        if (isGithubProxyAvailable()) {
            String proxyUrl = "http://" + GITHUB_PROXY_HOST + ":" + GITHUB_PROXY_PORT;
            env.put("HTTP_PROXY", proxyUrl);
            env.put("HTTPS_PROXY", proxyUrl);
            env.put("ALL_PROXY", proxyUrl);
            env.put("http_proxy", proxyUrl);
            env.put("https_proxy", proxyUrl);
            env.put("all_proxy", proxyUrl);
            env.put("NO_PROXY", "localhost,127.0.0.1");
            env.put("no_proxy", "localhost,127.0.0.1");
            env.put("GIT_CONFIG_COUNT", "2");
            env.put("GIT_CONFIG_KEY_0", "http.proxy");
            env.put("GIT_CONFIG_VALUE_0", proxyUrl);
            env.put("GIT_CONFIG_KEY_1", "https.proxy");
            env.put("GIT_CONFIG_VALUE_1", proxyUrl);
            env.put("ELECTRON_GET_USE_PROXY", "true");
        }
        return env;
    }

    private Map<String, String> verificationEnv() {
        Map<String, String> env = githubEnv();
        env.put("GOPROXY", DEFAULT_GO_PROXY);
        env.put("GOSUMDB", DEFAULT_GO_SUMDB);
        if (shouldUsePureGoLocalVerification()) {
            env.put("CGO_ENABLED", "0");
        }
        return env;
    }

    private Map<String, String> verificationEnv(Path packagePath) {
        Map<String, String> env = verificationEnv();
        String existingPath = System.getenv("PATH");
        String runtimeBin = packagePath.resolve(".runtime/bin").toAbsolutePath().toString();
        String runtimeGoBin = packagePath.resolve(".runtime/go/bin").toAbsolutePath().toString();
        env.put("PATH", runtimeBin + ":" + runtimeGoBin + (StringUtils.hasText(existingPath) ? ":" + existingPath : ""));
        return env;
    }

    private boolean shouldUsePureGoLocalVerification() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("mac")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("xcrun", "--find", "clang")
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(3, TimeUnit.SECONDS);
            return !completed || process.exitValue() != 0;
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private boolean isGithubProxyAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(GITHUB_PROXY_HOST, GITHUB_PROXY_PORT),
                    GITHUB_PROXY_CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String candidateCsv(SweTaskEntity task) {
        List<String> headers = List.of(
                "candidate_id", "repo", "repo_url", "pr_url", "issue_url",
                "issue_numbers", "problem_statement", "hints_text",
                "base_commit", "fix_commit", "merge_commit", "primary_language", "secondary_languages",
                "issue_specificity", "issue_categories",
                "repo_duplicate_status", "benchmark_risk", "source_type", "problem_type",
                "patch_files", "source_files", "insertions", "deletions", "total_changed",
                "gold_patch_files", "gold_source_files", "gold_insertions", "gold_deletions", "gold_total_changed",
                "test_patch_files", "test_insertions", "test_deletions", "test_total_changed",
                "test_patch_present", "fail_to_pass", "pass_to_pass", "benchmark_status", "failed_history_status",
                "generated_or_i18n_ratio", "score", "testability", "env_risk", "model_difficulty_guess",
                "candidate_status", "owner", "notes"
        );
        SweCandidateEntity candidate = resolveCandidateForTask(task);
        Map<String, String> row = new HashMap<>();
        row.put("candidate_id", candidate == null || candidate.getId() == null ? "selected" : "CAND-DB-" + candidate.getId());
        row.put("repo", task.getRepo());
        row.put("repo_url", "https://github.com/" + task.getRepo());
        row.put("pr_url", task.getSourceUrl());
        row.put("issue_url", candidate == null ? "" : candidate.getIssueUrl());
        row.put("issue_numbers", candidate == null ? "[]" : defaultJsonArray(candidate.getIssueNumbers()));
        row.put("problem_statement", candidate == null ? "" : candidate.getProblemStatement());
        row.put("hints_text", candidate == null ? "" : candidate.getHintsText());
        row.put("base_commit", task.getBaseCommit());
        row.put("fix_commit", task.getFixCommit());
        row.put("merge_commit", candidate == null ? "" : candidate.getMergeCommit());
        row.put("primary_language", StringUtils.hasText(task.getRepoLanguage()) ? task.getRepoLanguage() : "go");
        row.put("secondary_languages", candidate == null ? "" : candidate.getSecondaryLanguages());
        row.put("issue_specificity", defaultJsonArray(task.getIssueSpecificity()));
        row.put("issue_categories", defaultJsonArray(task.getIssueCategories()));
        row.put("repo_duplicate_status", "new");
        row.put("benchmark_risk", "");
        row.put("source_type", "github_merged_pr");
        row.put("problem_type", "real_bug_or_feature");
        row.put("patch_files", stringValue(candidate == null ? null : candidate.getPatchFiles()));
        row.put("source_files", stringValue(candidate == null ? null : candidate.getSourceFiles()));
        row.put("insertions", stringValue(candidate == null ? null : candidate.getInsertions()));
        row.put("deletions", stringValue(candidate == null ? null : candidate.getDeletions()));
        row.put("total_changed", stringValue(candidate == null ? null : candidate.getTotalChanged()));
        row.put("gold_patch_files", stringValue(candidate == null ? null : candidate.getGoldPatchFiles()));
        row.put("gold_source_files", stringValue(candidate == null ? null : candidate.getGoldSourceFiles()));
        row.put("gold_insertions", stringValue(candidate == null ? null : candidate.getGoldInsertions()));
        row.put("gold_deletions", stringValue(candidate == null ? null : candidate.getGoldDeletions()));
        row.put("gold_total_changed", stringValue(candidate == null ? null : candidate.getGoldTotalChanged()));
        row.put("test_patch_files", stringValue(candidate == null ? null : candidate.getTestPatchFiles()));
        row.put("test_insertions", stringValue(candidate == null ? null : candidate.getTestInsertions()));
        row.put("test_deletions", stringValue(candidate == null ? null : candidate.getTestDeletions()));
        row.put("test_total_changed", stringValue(candidate == null ? null : candidate.getTestTotalChanged()));
        row.put("test_patch_present", stringValue(candidate != null && Boolean.TRUE.equals(candidate.getTestPatchPresent())));
        row.put("fail_to_pass", candidate == null ? "[]" : defaultJsonArray(candidate.getFailToPass()));
        row.put("pass_to_pass", candidate == null ? "[]" : defaultJsonArray(candidate.getPassToPass()));
        row.put("benchmark_status", candidate == null || !StringUtils.hasText(candidate.getBenchmarkStatus())
                ? "UNKNOWN"
                : candidate.getBenchmarkStatus());
        row.put("failed_history_status", candidate == null || !StringUtils.hasText(candidate.getFailedHistoryStatus())
                ? "UNKNOWN"
                : candidate.getFailedHistoryStatus());
        row.put("generated_or_i18n_ratio", stringValue(candidate == null ? null : candidate.getGeneratedOrI18nRatio()));
        row.put("score", stringValue(candidate == null || candidate.getScore() == null ? 70 : candidate.getScore()));
        row.put("testability", "medium");
        row.put("env_risk", "medium");
        row.put("model_difficulty_guess", "medium");
        row.put("candidate_status", "selected");
        row.put("owner", "fly-agent");
        row.put("notes", "created from fly-agent SWE-Pro pipeline");

        StringBuilder builder = new StringBuilder();
        builder.append(String.join(",", headers)).append(System.lineSeparator());
        builder.append(headers.stream().map(header -> csv(row.get(header))).reduce((a, b) -> a + "," + b).orElse(""));
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    private String defaultJsonArray(String value) {
        return StringUtils.hasText(value) ? value : "[]";
    }

    private String packageName(SweTaskEntity task) {
        return packageName(task.getRepo(), prNumber(task.getSourceUrl()));
    }

    private String packageName(String repo, Integer prNumber) {
        return "production-task-" + slugRepo(repo) + "-" + (prNumber == null ? "manual" : prNumber);
    }

    private String slugRepo(String repo) {
        if (repo == null) {
            return "unknown";
        }
        String name = repo.contains("/") ? repo.substring(repo.lastIndexOf('/') + 1) : repo;
        return name.toLowerCase().replace('_', '-').replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-|-$)", "");
    }

    private Integer prNumber(String prUrl) {
        if (!StringUtils.hasText(prUrl)) {
            return null;
        }
        int index = prUrl.lastIndexOf('/');
        if (index < 0 || index == prUrl.length() - 1) {
            return null;
        }
        try {
            return Integer.parseInt(prUrl.substring(index + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<SweModelIoAttemptDTO> readModelIoAttempts(Path packagePath) {
        Path root = packagePath.resolve("model_evaluation");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> "model_api_raw_responses.jsonl".equals(path.getFileName().toString())
                            || "swe_agent_output.log".equals(path.getFileName().toString())
                            || "test_output.json".equals(path.getFileName().toString())
                            || "eval.log".equals(path.getFileName().toString()))
                    .map(Path::getParent)
                    .filter(path -> path != null && path.getFileName() != null
                            && path.getFileName().toString().startsWith("run_"))
                    .distinct()
                    .sorted(Comparator.comparing(this::pathLastModified).reversed())
                    .map(runDir -> readModelIoAttempt(packagePath, runDir))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private SweModelIoAttemptDTO readModelIoAttempt(Path packagePath, Path runDir) {
        SweModelIoAttemptDTO attempt = new SweModelIoAttemptDTO();
        attempt.setRunDir(runDir.toString());
        Path evaluationDir = runDir.getParent();
        attempt.setEvaluationName(evaluationDir == null ? "" : evaluationDir.getFileName().toString());
        attempt.setAttempt(parseAttemptNumber(runDir.getFileName().toString()));

        Path testOutput = runDir.resolve("test_output.json");
        if (Files.isRegularFile(testOutput)) {
            try {
                JSONObject json = readJson(testOutput);
                attempt.setStatus(json.getString("status"));
                attempt.setError(json.getString("error"));
            } catch (Exception ignored) {
                // Best-effort console; raw logs below still show what happened.
            }
        }

        Path rawResponse = runDir.resolve("model_api_raw_responses.jsonl");
        attempt.setRawResponsePath(rawResponse.toString());
        attempt.setRawResponseBytes(fileSize(rawResponse));
        attempt.setResponses(readRawModelResponses(rawResponse));
        attempt.setRawResponseLines(attempt.getResponses().size());

        Path sweAgentOutput = runDir.resolve("swe_agent_output.log");
        attempt.setSweAgentOutputPath(sweAgentOutput.toString());
        attempt.setSweAgentOutputBytes(fileSize(sweAgentOutput));
        String outputText = readTextIfRegular(sweAgentOutput);
        attempt.setSweAgentOutputTail(tailText(outputText, MODEL_IO_MAX_TEXT_CHARS));
        attempt.setModelInputBlocks(extractModelInputBlocks(outputText));

        if (!StringUtils.hasText(attempt.getError())) {
            String evalLog = readTextIfRegular(runDir.resolve("eval.log"));
            Matcher matcher = Pattern.compile("ERROR:\\s*(.*)").matcher(evalLog);
            if (matcher.find()) {
                attempt.setError(matcher.group(1).trim());
            }
        }
        if (!StringUtils.hasText(attempt.getStatus())) {
            attempt.setStatus(StringUtils.hasText(attempt.getError()) ? "error" : "running_or_pending");
        }
        return attempt;
    }

    private List<SweModelIoResponseDTO> readRawModelResponses(Path rawResponse) {
        if (!Files.isRegularFile(rawResponse)) {
            return List.of();
        }
        List<SweModelIoResponseDTO> responses = new ArrayList<>();
        try (Stream<String> lines = Files.lines(rawResponse, StandardCharsets.UTF_8)) {
            lines.filter(StringUtils::hasText)
                    .limit(MODEL_IO_MAX_RAW_RESPONSES_PER_ATTEMPT)
                    .forEach(line -> responses.add(readRawModelResponse(line)));
        } catch (IOException ignored) {
            return responses;
        }
        return responses;
    }

    private SweModelIoResponseDTO readRawModelResponse(String line) {
        SweModelIoResponseDTO response = new SweModelIoResponseDTO();
        response.setRawJson(tailText(line, MODEL_IO_MAX_TEXT_CHARS));
        try {
            JSONObject json = JSON.parseObject(line);
            response.setApiCallIndex(json.getInteger("api_call_index"));
            response.setTimestamp(json.getDouble("timestamp"));
            response.setConfiguredModel(json.getString("configured_model"));
            response.setProvider(json.getString("provider"));
            JSONObject body = json.getJSONObject("response");
            if (body != null) {
                response.setResponseId(body.getString("id"));
                response.setResponseModel(body.getString("model"));
                JSONObject usage = body.getJSONObject("usage");
                if (usage != null) {
                    response.setPromptTokens(usage.getInteger("prompt_tokens"));
                    response.setCompletionTokens(usage.getInteger("completion_tokens"));
                    response.setTotalTokens(usage.getInteger("total_tokens"));
                }
                JSONArray choices = body.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject choice = choices.getJSONObject(0);
                    response.setFinishReason(choice.getString("finish_reason"));
                    JSONObject message = choice.getJSONObject("message");
                    if (message != null) {
                        response.setAssistantContent(message.getString("content"));
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep rawJson even when a provider writes a non-standard JSON line.
        }
        return response;
    }

    private List<String> extractModelInputBlocks(String outputText) {
        if (!StringUtils.hasText(outputText)) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        Pattern pattern = Pattern.compile("🤖 MODEL INPUT\\s*\\R(.*?)(?=\\R\\s*(?:🤠 INFO\\s*=+ STEP|🤖 DEBUG|🔧 DEBUG|\\[swe-agent-eval|$))", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(outputText);
        while (matcher.find() && blocks.size() < MODEL_IO_MAX_MODEL_INPUT_BLOCKS_PER_ATTEMPT) {
            blocks.add(tailText(matcher.group(1).strip(), MODEL_IO_MAX_TEXT_CHARS));
        }
        return blocks;
    }

    private Integer parseAttemptNumber(String name) {
        Matcher matcher = Pattern.compile("run_(\\d+)").matcher(name == null ? "" : name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private String readTextIfRegular(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return tailText(Files.readString(path, StandardCharsets.UTF_8), MODEL_IO_MAX_TEXT_CHARS);
        } catch (IOException e) {
            return "";
        }
    }

    private String tailText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(text.length() - maxChars);
    }

    private Long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private java.nio.file.attribute.FileTime pathLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private Path findLatestSummary(Path packagePath, String key) {
        Path root = packagePath.resolve("model_evaluation");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(root, 2)) {
            String lower = key.toLowerCase();
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> "summary.json".equals(path.getFileName().toString()))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().toLowerCase().contains(lower))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path findLatestReusableModelSummary(Path packagePath, String key) {
        Path root = packagePath.resolve("model_evaluation");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(root, 2)) {
            String lower = key.toLowerCase();
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> "summary.json".equals(path.getFileName().toString()))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().toLowerCase().contains(lower))
                    .filter(path -> {
                        try {
                            JSONObject summary = readJson(path);
                            validateInspectedModelSummaryGate(key, summary);
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private List<Path> findMatchingFiles(Path root, String... suffixes) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        for (String suffix : suffixes) {
                            if (name.endsWith(suffix)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<Path> findDeliveryFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, 8)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        Path relative = root.relativize(path);
                        if (relative.getNameCount() == 0) {
                            return false;
                        }
                        String first = relative.getName(0).toString();
                        return !"repo".equals(first);
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String extractCommitFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        int index = url.lastIndexOf('/');
        return index >= 0 ? url.substring(index + 1) : url;
    }

    private SweTaskEntity requireTask(Long id) {
        SweTaskEntity task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ERROR_TASK_NOT_FOUND);
        }
        return task;
    }

    private Path requireSamplePath(String samplePathText) {
        if (!StringUtils.hasText(samplePathText)) {
            throw new BusinessException("samplePath is required for this pipeline");
        }
        Path samplePath = requireDirectory(Path.of(samplePathText), "samplePath does not exist");
        if (!Files.isRegularFile(samplePath.resolve("task.json"))) {
            throw new BusinessException("samplePath mode requires task.json; clear samplePath to run from a GitHub PR candidate");
        }
        return samplePath;
    }

    private Path requireDirectory(Path path, String message) {
        if (!Files.isDirectory(path)) {
            throw new BusinessException(message + ": " + path);
        }
        return path;
    }

    private Path requireFile(Path path, String message) {
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(message + ": " + path);
        }
        return path;
    }

    private JSONObject readJson(Path path) {
        try {
            return JSON.parseObject(Files.readString(path));
        } catch (IOException e) {
            throw new BusinessException("failed to read json: " + path, e);
        }
    }

    private long countLines(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path)) {
            return lines.count();
        }
    }

    private TaskSpecSnapshot taskSpecSnapshot(Path packagePath) {
        try {
            Map<String, String> checksums = new LinkedHashMap<>();
            for (String relative : taskSpecFiles()) {
                Path path = packagePath.resolve(relative);
                checksums.put(relative, Files.isRegularFile(path) ? sha256(path) : null);
            }
            return new TaskSpecSnapshot(checksums);
        } catch (IOException e) {
            throw new BusinessException("failed to snapshot task spec", e);
        }
    }

    private void assertTaskSpecUnchanged(Path packagePath, TaskSpecSnapshot snapshot, String operation) {
        TaskSpecSnapshot current = taskSpecSnapshot(packagePath);
        if (!snapshot.checksums().equals(current.checksums())) {
            throw new BusinessException("task spec changed during " + operation
                    + "; validation/evaluation stages must consume immutable task artifacts");
        }
    }

    private List<String> taskSpecFiles() {
        return List.of(
                "task.json",
                "patches/gold.patch",
                "patches/test.patch",
                "scripts/run_selected_tests.sh",
                "scripts/verify_patch_application.sh",
                "dockerfiles/Dockerfile"
        );
    }

    private void recordArtifact(Long runId, String type, Path path) {
        try {
            SweArtifactEntity artifact = new SweArtifactEntity();
            artifact.setRunId(runId);
            artifact.setArtifactType(type);
            artifact.setArtifactName(path.getFileName().toString());
            artifact.setArtifactPath(path.toAbsolutePath().toString());
            artifact.setFileSize(Files.isRegularFile(path) ? Files.size(path) : null);
            artifact.setChecksum(Files.isRegularFile(path) ? sha256(path) : null);
            artifact.setCreatedAt(LocalDateTime.now());
            artifactMapper.insert(artifact);
        } catch (IOException e) {
            throw new BusinessException("failed to index artifact: " + path, e);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(OutputStreamDiscard.INSTANCE);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IOException("failed to calculate sha256: " + path, e);
        }
    }

    private record TaskSpecSnapshot(Map<String, String> checksums) {
    }

    private SwePipelineStageEntity newStage(Long runId, SwePipelineStage stage) {
        SwePipelineStageEntity entity = new SwePipelineStageEntity();
        entity.setRunId(runId);
        entity.setStageCode(stage.getCode());
        entity.setStageName(stage.getDescription());
        entity.setStatus(SwePipelineStatus.CREATED.getCode());
        entity.setSortOrder(stage.getSortOrder());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private SwePipelineStageEntity getStage(Long runId, SwePipelineStage stage) {
        return stageMapper.selectOne(new LambdaQueryWrapper<SwePipelineStageEntity>()
                .eq(SwePipelineStageEntity::getRunId, runId)
                .eq(SwePipelineStageEntity::getStageCode, stage.getCode()));
    }

    private void markRunRunning(Long runId) {
        SwePipelineRunEntity run = runMapper.selectById(runId);
        run.setStatus(SwePipelineStatus.RUNNING.getCode());
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(null);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        clearRunFinishedAt(runId);
        clearRunErrorMessage(runId);
    }

    private void clearRunFinishedAt(Long runId) {
        runMapper.update(null, new LambdaUpdateWrapper<SwePipelineRunEntity>()
                .set(SwePipelineRunEntity::getFinishedAt, null)
                .eq(SwePipelineRunEntity::getId, runId));
    }

    private void updateCurrentStage(Long runId, String stageCode) {
        SwePipelineRunEntity run = runMapper.selectById(runId);
        run.setCurrentStage(stageCode);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private void completeRun(Long runId) {
        SwePipelineRunEntity run = runMapper.selectById(runId);
        run.setStatus(SwePipelineStatus.COMPLETED.getCode());
        run.setFinishedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        clearRunErrorMessage(runId);
    }

    private void clearRunErrorMessage(Long runId) {
        runMapper.update(null, new LambdaUpdateWrapper<SwePipelineRunEntity>()
                .set(SwePipelineRunEntity::getErrorMessage, null)
                .eq(SwePipelineRunEntity::getId, runId));
    }

    private void clearStageErrorMessage(Long stageId) {
        stageMapper.update(null, new LambdaUpdateWrapper<SwePipelineStageEntity>()
                .set(SwePipelineStageEntity::getErrorMessage, null)
                .eq(SwePipelineStageEntity::getId, stageId));
    }

    private void clearStageFinishedAt(Long stageId) {
        stageMapper.update(null, new LambdaUpdateWrapper<SwePipelineStageEntity>()
                .set(SwePipelineStageEntity::getFinishedAt, null)
                .eq(SwePipelineStageEntity::getId, stageId));
    }

    private void markTaskStatus(Long taskId, String status) {
        SweTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        if (SwePipelineStatus.COMPLETED.getCode().equals(status)
                || SwePipelineStatus.DELIVERED.getCode().equals(status)) {
            markCandidateDelivered(task);
        }
    }

    private void markCandidateDelivered(SweTaskEntity task) {
        SweCandidateEntity candidate = resolveCandidateForTask(task);
        if (candidate == null) {
            return;
        }
        candidate.setCandidateStatus("delivered");
        candidate.setDuplicateStatus("DELIVERED");
        candidate.setModifiedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
    }

    private void markCandidateStatus(SweTaskEntity task, String status) {
        if (task == null) {
            return;
        }
        if (task.getCandidateId() == null) {
            attachCandidate(task);
        }
        markCandidateStatus(task.getCandidateId(), status);
    }

    private void markCandidateStatus(Long candidateId, String status) {
        if (candidateId == null || !StringUtils.hasText(status)) {
            return;
        }
        SweCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            return;
        }
        candidate.setCandidateStatus(status);
        candidate.setModifiedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
    }

    private void updateTaskSamplePath(Long taskId, Path samplePath) {
        SweTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || samplePath == null) {
            return;
        }
        task.setSamplePath(samplePath.toAbsolutePath().toString());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void failRun(Long runId, String errorMessage) {
        SwePipelineRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        run.setStatus(SwePipelineStatus.FAILED.getCode());
        run.setErrorMessage(errorMessage);
        run.setFinishedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        markCandidateStatus(run.getCandidateId(), "failed");
    }

    private SweTaskDTO toTaskDTO(SweTaskEntity entity, boolean includeRuns) {
        SweTaskDTO dto = new SweTaskDTO();
        BeanUtils.copyProperties(entity, dto);
        if (includeRuns) {
            dto.setRecentRuns(listRuns(entity.getId()).stream().limit(10).toList());
        }
        return dto;
    }

    private SwePipelineRunDTO toRunDTO(SwePipelineRunEntity entity, boolean includeDetails) {
        SwePipelineRunDTO dto = new SwePipelineRunDTO();
        BeanUtils.copyProperties(entity, dto);
        if (includeDetails) {
            dto.setStages(stageMapper.selectList(new LambdaQueryWrapper<SwePipelineStageEntity>()
                            .eq(SwePipelineStageEntity::getRunId, entity.getId())
                            .orderByAsc(SwePipelineStageEntity::getSortOrder))
                    .stream()
                    .map(this::toStageDTO)
                    .toList());
            dto.setArtifacts(artifactMapper.selectList(new LambdaQueryWrapper<SweArtifactEntity>()
                            .eq(SweArtifactEntity::getRunId, entity.getId())
                            .orderByDesc(SweArtifactEntity::getCreatedAt))
                    .stream()
                    .map(this::toArtifactDTO)
                    .toList());
        }
        return dto;
    }

    private SweStageDTO toStageDTO(SwePipelineStageEntity entity) {
        SweStageDTO dto = new SweStageDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    private SweArtifactDTO toArtifactDTO(SweArtifactEntity entity) {
        SweArtifactDTO dto = new SweArtifactDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    @FunctionalInterface
    private interface StageAction {
        String execute() throws Exception;
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {
        private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

        @Override
        public void write(int b) {
            // discard
        }
    }
}
