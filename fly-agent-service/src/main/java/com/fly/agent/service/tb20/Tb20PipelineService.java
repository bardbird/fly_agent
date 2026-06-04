package com.fly.agent.service.tb20;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.tb20.Tb20BlueprintResponse;
import com.fly.agent.common.dto.tb20.Tb20DependencyStatusDTO;
import com.fly.agent.common.dto.tb20.Tb20InspectRequest;
import com.fly.agent.common.dto.tb20.Tb20PipelineResponse;
import com.fly.agent.common.dto.tb20.Tb20StageDTO;
import com.fly.agent.common.exception.BusinessException;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads real Terminal-Bench 2.0 task packages and produces standardized
 * delivery manifests. The service does not mock or synthesize task results.
 */
@Service
@RequiredArgsConstructor
public class Tb20PipelineService {

    private static final List<String> REQUIRED_TASK_FILES = List.of(
            "task.toml",
            "instruction.md",
            "environment/Dockerfile",
            "solution/solve.sh",
            "tests/test.sh",
            "tests/test_outputs.py");

    private static final List<String> OPTIONAL_DELIVERY_LOGS = List.of(
            "agent-logs/run.json",
            "agent-logs/trajectory.json",
            "agent-logs/verifier/ctrf.json",
            "agent-logs/verifier/reward.txt");

    private final Tb20Properties properties;

    public Tb20BlueprintResponse blueprint(String harborRoot, String terminalBenchRoot) {
        Tb20BlueprintResponse response = new Tb20BlueprintResponse();
        response.setRequiredTaskFiles(new ArrayList<>(REQUIRED_TASK_FILES));
        response.setOptionalDeliveryLogs(new ArrayList<>(OPTIONAL_DELIVERY_LOGS));
        response.setStages(List.of(
                stage("TOPIC_DESIGN", "选题与能力点设计", "AI辅助，人工/专家门禁", "单体/批量", "Claude Code + domain skill",
                        "领域、难度、能力标签、约束", "task brief", "题目真实、有区分度、无敏感/版权风险"),
                stage("TASK_SCAFFOLD", "TB 2.0 目录骨架生成", "可自动化", "单体/批量", "脚本",
                        "task brief", "task.toml/instruction/environment/tests/solution skeleton", "标准文件齐全"),
                stage("REFERENCE_SOLUTION", "参考解构建", "AI辅助，必须验证", "单体/批量", "Claude Code / Codex / skill",
                        "题面和环境", "solution/solve.sh", "oracle 可通过 verifier"),
                stage("TEST_CONSTRUCTION", "公开与隐藏测试构建", "AI辅助，人工门禁", "单体/批量", "Claude Code + pytest skill",
                        "题面、参考解、投机路径清单", "tests/test.sh + tests/test_outputs.py", "参考解通过，空解/投机解失败"),
                stage("HARBOR_VERIFY", "Harbor/TB 2.0 运行验收", "可自动化", "单体/批量", "Harbor",
                        "标准 task", "reward、CTRF、trajectory", "reward=1 且重跑稳定"),
                stage("DIFFICULTY_CALIBRATION", "多模型难度校准", "可批量，结论需复核", "批量", "Harbor + models",
                        "任务集", "通过率、耗时、token、失败类型", "easy/medium/hard 分布合理"),
                stage("DELIVERY_PACKAGE", "标准化交付包产出", "可自动化", "单体/批量", "脚本",
                        "任务目录和日志", "delivery_manifest.json + task package", "结构、checksum、reward、报告齐全"),
                stage("FINAL_AUDIT", "最终质检审计", "AI辅助，人工门禁", "单体/批量", "review skill + Claude Code",
                        "交付包", "审计报告", "无占位符、无泄漏、无不可复现依赖")
        ));
        response.setNonAutomatableBoundaries(List.of(
                "领域选题价值判断不能完全自动化，只能由模型生成候选并由专家/审计规则筛选",
                "hard 任务的算法、协议、逆向、系统语义正确性不能只靠模型自证",
                "参考解是否合理体现 expert path 需要代码审查和运行证据",
                "hidden tests、抗 hardcode、抗读取测试文件的设计需要人工或独立 reviewer agent 复核",
                "难度分层最终要用多模型通过率和人工解释共同确认",
                "版权、许可证、敏感信息、外部依赖可复现性必须保留最终门禁"
        ));
        response.setAiScaleOutControls(List.of(
                "页面单体触发：针对一个 task brief/目录启动 Claude Code 或 skill 生成/修复/审计",
                "页面批量触发：对任务池逐个排队执行，不在后台无限自发生产",
                "每个 AI 产物必须落盘为 instruction、solution、tests 或 audit report，并进入 verifier",
                "Claude Code/Codex 只能代替编辑和初审，不能跳过 oracle reward、dummy failure、hidden-test review",
                "批量模式必须记录 prompt、模型、版本、输入 brief、输出 diff 和失败原因"
        ));
        response.setDependencies(checkDependencies(harborRoot, terminalBenchRoot));
        return response;
    }

    public List<Tb20DependencyStatusDTO> checkDependencies(String harborRoot, String terminalBenchRoot) {
        return List.of(
                dependency("Terminal-Bench 2.0", "标准任务集合和评测目标",
                        firstText(terminalBenchRoot, properties.getTerminalBenchRoot()), "README.md"),
                dependency("Harbor", "TB 2.0 官方推荐 runner/harness",
                        firstText(harborRoot, properties.getHarborRoot()), "README.md"),
                commandDependency("Docker", "构建和运行每个 task 的隔离环境", "docker"),
                commandDependency("pytest-json-ctrf", "verifier 生成 CTRF 测试报告，通常由 tests/test.sh 使用 uvx 拉取", "python3"),
                dependency("TB20 production toolkit", "本项目外置脚本边界",
                        properties.getToolkitRoot(), "scripts/inspect_tb20_dataset.py")
        );
    }

    public Tb20PipelineResponse inspect(Tb20InspectRequest request) {
        return runInspector("inspect", request, false);
    }

    public Tb20PipelineResponse runSingle(Tb20InspectRequest request) {
        if (request.getTaskPaths() == null || request.getTaskPaths().size() != 1) {
            throw new BusinessException("单体触发必须传入且只传入一个 taskPath");
        }
        return runInspector("single", request, true);
    }

    public Tb20PipelineResponse runBatch(Tb20InspectRequest request) {
        return runInspector("batch", request, true);
    }

    private Tb20PipelineResponse runInspector(String mode, Tb20InspectRequest request, boolean packageOutput) {
        String sourceRoot = firstText(request.getSourceRoot(), properties.getDefaultSourceRoot());
        String outputRoot = request.getOutputRoot();
        if (packageOutput && !StringUtils.hasText(outputRoot)) {
            outputRoot = Path.of(properties.getProductionRoot(),
                    mode + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                    .toString();
        }

        List<String> command = new ArrayList<>();
        command.add(properties.getPython());
        command.add(Path.of(properties.getToolkitRoot(), "scripts", "inspect_tb20_dataset.py").toString());
        command.add("--source-root");
        command.add(sourceRoot);
        if (StringUtils.hasText(outputRoot)) {
            command.add("--output-root");
            command.add(outputRoot);
            if (Boolean.TRUE.equals(request.getCopyTasks())) {
                command.add("--copy-tasks");
            }
        }
        if (request.getTaskPaths() != null) {
            for (String taskPath : request.getTaskPaths()) {
                if (StringUtils.hasText(taskPath)) {
                    command.add("--task");
                    command.add(taskPath);
                }
            }
        }

        JSONObject raw = runJsonCommand(command, Path.of("."));
        Tb20PipelineResponse response = new Tb20PipelineResponse();
        response.setMode(mode);
        response.setSourceRoot(raw.getString("sourceRoot"));
        response.setOutputRoot(outputRoot);
        response.setSummary(raw.getJSONObject("summary") == null ? new JSONObject() : raw.getJSONObject("summary"));
        response.setTasks(raw.getJSONArray("tasks") == null ? new JSONArray() : raw.getJSONArray("tasks"));
        response.setDependencies(checkDependencies(request.getHarborRoot(), request.getTerminalBenchRoot()));
        if (StringUtils.hasText(outputRoot)) {
            response.setManifestPath(Path.of(outputRoot, "delivery_manifest.json").toString());
            response.setDeliveryIndexPath(Path.of(outputRoot, "delivery_index.md").toString());
        }
        return response;
    }

    private JSONObject runJsonCommand(List<String> command, Path cwd) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!completed) {
                process.destroyForcibly();
                throw new BusinessException("TB 2.0 inspector timeout");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException("TB 2.0 inspector failed: " + output);
            }
            return JSON.parseObject(output);
        } catch (IOException e) {
            throw new BusinessException("failed to run TB 2.0 inspector", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("TB 2.0 inspector interrupted", e);
        }
    }

    private Tb20StageDTO stage(String code, String name, String automationLevel, String triggerMode,
                              String owner, String input, String output, String gate) {
        Tb20StageDTO stage = new Tb20StageDTO();
        stage.setCode(code);
        stage.setName(name);
        stage.setAutomationLevel(automationLevel);
        stage.setTriggerMode(triggerMode);
        stage.setOwner(owner);
        stage.setInput(input);
        stage.setOutput(output);
        stage.setGate(gate);
        return stage;
    }

    private Tb20DependencyStatusDTO dependency(String name, String role, String configuredPath, String probeFile) {
        Tb20DependencyStatusDTO dto = new Tb20DependencyStatusDTO();
        dto.setName(name);
        dto.setRole(role);
        dto.setConfiguredPath(configuredPath);
        if (!StringUtils.hasText(configuredPath)) {
            dto.setPresent(false);
            dto.setStatus("UNCONFIGURED");
            dto.setNote("未配置路径");
            return dto;
        }
        Path root = Path.of(configuredPath).toAbsolutePath().normalize();
        boolean present = Files.exists(root.resolve(probeFile));
        dto.setPresent(present);
        dto.setStatus(present ? "READY" : "MISSING");
        dto.setNote(present ? "路径可用" : "未找到 " + probeFile);
        return dto;
    }

    private Tb20DependencyStatusDTO commandDependency(String name, String role, String command) {
        Tb20DependencyStatusDTO dto = new Tb20DependencyStatusDTO();
        dto.setName(name);
        dto.setRole(role);
        dto.setConfiguredPath(command);
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            dto.setPresent(completed && process.exitValue() == 0);
            dto.setStatus(Boolean.TRUE.equals(dto.getPresent()) ? "READY" : "CHECK_FAILED");
            dto.setNote(Boolean.TRUE.equals(dto.getPresent()) ? "命令可执行" : "命令检查失败");
        } catch (Exception e) {
            dto.setPresent(false);
            dto.setStatus("MISSING");
            dto.setNote(e.getMessage());
        }
        return dto;
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }
}
