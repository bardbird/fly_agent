# ALE 后端编排层 Implementation Plan（Plan 2/3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task. Steps use checkbox (`- [ ]`).

**Goal:** 后端不再直连 codex、不再硬编码队列；新增 `AleExecutionGateway` 统一「写触发文件 + 重置 progress + 轮询终态 + 超时 + tailLog」，`AleStage1Service`/`AleStage2Service` 接入它，删除后端与 runner 重复的 codex 调用链与模糊匹配。

**Architecture:** Gateway 是编排核心：dispatchAndWait 原子写触发文件到共享队列、重置当前阶段 progress、阻塞轮询 `<stage>_progress.json` 的 `done/failed` 终态、超时即 FAILED；按 stage 读 `<stage>.log` 尾。两 Service 删旧逻辑、改调 gateway、按 exact task 契约传 `tasks`、`findOracleResult` 改 exact-only、`getOptions` 改读配置。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis-Plus、JUnit 5（`@TempDir`）+ Mockito（`mock`/`when`/`verify`/`ArgumentCaptor`）、fastjson2。

**系列：** ALE 重构第 2 个计划，依赖 Plan 1 的 runner 契约（trigger payload / `<stage>_progress.json` / phase 词表）。后续 Plan 3：daemon + 构建部署 + e2e。

**设计依据：** `docs/superpowers/specs/2026-06-22-ale-host-unified-design.md`（§4.2/§4.3/§6.1/§6.6/§8）。

---

## File Structure

- Modify: `fly-agent-service/.../ale/AleProperties.java` — 加 `queueDir`/`codexModels`/`stage1TimeoutMinutes`/`stage2TimeoutMinutes`，`outputRoot` 默认绝对路径
- Create: `fly-agent-service/.../ale/AleExecutionGateway.java` — 编排核心（dispatchAndWait + tailLog）
- Create: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AleExecutionGatewayTest.java`
- Modify: `fly-agent-service/.../ale/AleStage1Service.java` — executeRun 接 gateway + exact contract + 删 `buildCommand`/`runCodex`/`estimateProgress` 系列/`findOracleResult` suffix；`getOptions` 配置化
- Create: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AleStage1ServiceTest.java`
- Modify: `fly-agent-service/.../ale/AleStage2Service.java` — executeStage2 接 gateway + 删 `findRunnerScript`/`estimateStage2Progress`/`STAGE2_QUEUE_DIR`；tailLog 委托
- Create: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AleStage2ServiceTest.java`
- Modify: `fly-agent-server/src/main/resources/application.yml` + `application-dev.yml` — `ale:` 段加 queue-dir/codex-models/timeouts，output-root 绝对路径

**职责边界：** Gateway 只懂文件协议（触发文件/progress/log），不懂业务（Oracle/任务）；Service 只懂业务 + 调 gateway；DB 不加列（日志/progress 路径由 `output_root` 推导）。

---

## 共享约定

- **测试命令**：`mvn -pl fly-agent-service -am test -Dtest=AleExecutionGatewayTest`（或类名）。全模块：`mvn -pl fly-agent-service -am test`。
- **Mockito 风格**（参考 `SwePipelineServiceTest`）：`mock(Mapper.class)` + `when(...).thenReturn(...)` + `verify(...)` + `ArgumentCaptor`；`@TempDir Path tempDir`（JUnit 5）。
- **trigger payload**（gateway 写出，daemon 读，runner `--from-trigger` 消费）：
  ```json
  {"type":"stage1","run_id":123,"run_dir":"/.../<runKey>",
   "stage1":{"framework_root":"...","codex_model":"gpt-5.5",
             "tasks":[{"task_id":"d/t01","title":"T1"}],"request":{...}}}
  ```
  stage2 段把 `stage1` 换 `stage2:{framework_root,agent,model,timeout}`。
- **progress 文件**：`<run_dir>/<stage>_progress.json`，`{stage,phase,percent,counts,current_task,message}`，终态 `done`/`failed`。
- **phase 判定**：gateway 只认 `done`/`failed`；其余继续轮询至超时。不关心触发文件是否删除。

---

## Task 1: AleProperties 字段扩展

**Files:**
- Modify: `fly-agent-service/.../ale/AleProperties.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AlePropertiesTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.fly.agent.service.ale;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlePropertiesTest {
    @Test
    void defaultsAreProductionAbsolutePathsAndLists() {
        AleProperties p = new AleProperties();
        assertEquals("/data/fly-agent/ale-runs", p.getOutputRoot());
        assertEquals("/home/ubuntu/agents-last-exam", p.getFrameworkRoot());
        assertEquals("/data/fly-agent/ale-runs/.queue", p.getQueueDir());
        assertEquals(90, p.getStage1TimeoutMinutes());
        assertEquals(240, p.getStage2TimeoutMinutes());
        assertTrue(p.getCodexModels().contains("gpt-5.5"));
    }
}
```

- [ ] **Step 2: 跑确认失败** — `mvn -pl fly-agent-service -am test -Dtest=AlePropertiesTest` → FAIL（getQueueDir 等方法不存在）

- [ ] **Step 3: 改 AleProperties**

把 `AleProperties.java` 整体改为：
```java
package com.fly.agent.service.ale;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ale")
public class AleProperties {

    private String codexBinary = "codex";
    private String outputRoot = "/data/fly-agent/ale-runs";
    private String frameworkRoot = "/home/ubuntu/agents-last-exam";
    private String queueDir = "/data/fly-agent/ale-runs/.queue";
    private List<String> codexModels = List.of("gpt-5.5", "gpt-5-mini", "gpt-5-codex");
    private int stage1TimeoutMinutes = 90;
    private int stage2TimeoutMinutes = 240;
}
```

- [ ] **Step 4: 跑确认通过** — PASS
- [ ] **Step 5: Commit** — `feat(ale): extend AleProperties with queue-dir, codex-models, timeouts, absolute output-root`

---

## Task 2: AleExecutionGateway（编排核心）

**Files:**
- Create: `fly-agent-service/.../ale/AleExecutionGateway.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AleExecutionGatewayTest.java`

> Gateway 是本计划最复杂单元。测试用 `@TempDir` 模拟 queue + runDir，异步线程写 `<stage>_progress.json` 模拟 daemon/runner 行为。

- [ ] **Step 1: 写失败测试（dispatch + 终态 + 触发文件原子写 + tailLog）**

```java
package com.fly.agent.service.ale;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AleExecutionGatewayTest {

    @TempDir
    Path tempDir;
    private AleProperties props;
    private AleExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        props = new AleProperties();
        props.setQueueDir(tempDir.resolve("queue").toString());
        props.setStage1TimeoutMinutes(90);
        props.setStage2TimeoutMinutes(240);
        gateway = new AleExecutionGateway(props);
    }

    @Test
    void dispatchWritesTriggerAtomicallyAndResetsProgress() throws Exception {
        Path runDir = tempDir.resolve("run-1");
        Files.createDirectories(runDir);
        // 预置一个陈旧的 done（模拟 stage2 重跑），dispatch 前应被重置为 starting
        Files.writeString(runDir.resolve("stage2_progress.json"),
                "{\"stage\":\"stage2\",\"phase\":\"done\",\"percent\":100}");

        Map<String, Object> payload = Map.of(
                "type", "stage2", "run_id", 1, "run_dir", runDir.toString(),
                "stage2", Map.of("framework_root", "/fw"));

        AtomicInteger observedPercent = new AtomicInteger(-1);
        CountDownLatch triggerWritten = new CountDownLatch(1);

        // 异步：等触发文件出现后，写一个 failed 终态，让 dispatch 返回
        Thread daemon = new Thread(() -> {
            try {
                Path trigger = tempDir.resolve("queue").resolve("1.json");
                // 轮询等触发文件
                for (int i = 0; i < 200 && !Files.exists(trigger); i++) Thread.sleep(25);
                triggerWritten.countDown();
                // 校验 progress 已被重置为 starting（非陈旧 done）
                String prog = Files.readString(runDir.resolve("stage2_progress.json"));
                assertTrue(prog.contains("\"starting\""), "progress should be reset to starting");
                // 写 failed 终态
                Files.writeString(runDir.resolve("stage2_progress.json"),
                        "{\"stage\":\"stage2\",\"phase\":\"failed\",\"percent\":100,\"message\":\"boom\"}");
            } catch (Exception ignored) {}
        });
        daemon.setDaemon(true);
        daemon.start();

        AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                1L, runDir, payload, observedPercent::set);

        assertTrue(triggerWritten.await(5, TimeUnit.SECONDS));
        assertEquals("failed", result.phase());
        assertEquals("boom", result.message());

        // 触发文件存在且内容正确（原子写：无 .tmp 残留）
        Path trigger = tempDir.resolve("queue").resolve("1.json");
        assertTrue(Files.exists(trigger));
        assertFalse(Files.exists(tempDir.resolve("queue").resolve("1.json.tmp")));
        JSONObject parsed = JSON.parseObject(Files.readString(trigger));
        assertEquals("stage2", parsed.getString("type"));
    }

    @Test
    void dispatchReturnsDoneWhenPhaseDone() throws Exception {
        Path runDir = tempDir.resolve("run-2");
        Files.createDirectories(runDir);
        Map<String, Object> payload = Map.of(
                "type", "stage1", "run_id", 2, "run_dir", runDir.toString(),
                "stage1", Map.of("framework_root", "/fw"));
        Thread daemon = new Thread(() -> {
            try {
                Path trigger = tempDir.resolve("queue").resolve("2.json");
                for (int i = 0; i < 200 && !Files.exists(trigger); i++) Thread.sleep(25);
                Thread.sleep(50);
                Files.writeString(runDir.resolve("stage1_progress.json"),
                        "{\"stage\":\"stage1\",\"phase\":\"done\",\"percent\":100}");
            } catch (Exception ignored) {}
        });
        daemon.setDaemon(true);
        daemon.start();

        AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                2L, runDir, payload, p -> {});
        assertEquals("done", result.phase());
    }

    @Test
    void dispatchTimesOutWhenNoTerminalPhase() throws Exception {
        Path runDir = tempDir.resolve("run-3");
        Files.createDirectories(runDir);
        props.setStage1TimeoutMinutes(0);  // 用配置覆盖：0 分钟超时（立即）
        Map<String, Object> payload = Map.of(
                "type", "stage1", "run_id", 3, "run_dir", runDir.toString(),
                "stage1", Map.of("framework_root", "/fw"));
        AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                3L, runDir, payload, p -> {});
        assertEquals("failed", result.phase());
        assertTrue(result.message().contains("timeout"));
    }

    @Test
    void tailLogReturnsLastNLines() throws Exception {
        Path runDir = tempDir.resolve("run-4");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("stage1.log"),
                "line1\nline2\nline3\nline4\n", StandardCharsets.UTF_8);
        List<String> tail = gateway.tailLog(runDir, "stage1", 2);
        assertEquals(List.of("line3", "line4"), tail);
    }
}
```

- [ ] **Step 2: 跑确认失败** — FAIL（`AleExecutionGateway` 不存在）

- [ ] **Step 3: 实现 AleExecutionGateway**

```java
package com.fly.agent.service.ale;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AleExecutionGateway {

    private final AleProperties properties;

    public record StageResult(String phase, String message) {
        public boolean isDone() { return "done".equals(phase); }
        public boolean isFailed() { return "failed".equals(phase); }
    }

    /** 写触发文件 + 重置当前阶段 progress + 阻塞轮询至 done/failed 或超时。 */
    public StageResult dispatchAndWait(Long runId, Path runDir, Map<String, Object> payload,
                                       IntConsumer onProgress) {
        String type = (String) payload.get("type");
        Path progressFile = runDir.resolve(type + "_progress.json");

        try {
            // 1. 重置当前阶段 progress（避免 stage2 重跑读到上次 done）
            writeProgress(progressFile, type, "starting", 0, null);

            // 2. 原子写触发文件
            Path queueDir = Path.of(properties.getQueueDir());
            Files.createDirectories(queueDir);
            Path tmp = queueDir.resolve(runId + ".json.tmp");
            Path trigger = queueDir.resolve(runId + ".json");
            Files.writeString(tmp, JSON.toJSONString(payload), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, trigger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("ALE dispatch: type={} runId={} trigger={}", type, runId, trigger);

            // 3. 轮询至终态或超时（gateway 不关心触发文件是否被删）
            int timeoutMinutes = "stage2".equals(type)
                    ? properties.getStage2TimeoutMinutes() : properties.getStage1TimeoutMinutes();
            long deadline = System.currentTimeMillis() + Math.max(0, timeoutMinutes) * 60_000L;
            int lastPercent = -1;
            // 超时为 0 时至少轮询一次以读取当前 progress，再判超时
            while (true) {
                JSONObject frame = readProgress(progressFile);
                if (frame != null) {
                    Integer percent = frame.getInteger("percent");
                    if (percent != null && percent > lastPercent) {
                        lastPercent = percent;
                        onProgress.accept(percent);
                    }
                    String phase = frame.getString("phase");
                    if ("done".equals(phase)) return new StageResult("done", frame.getString("message"));
                    if ("failed".equals(phase)) return new StageResult("failed", frame.getString("message"));
                }
                if (System.currentTimeMillis() >= deadline) {
                    return new StageResult("failed", "timeout after " + timeoutMinutes + "min");
                }
                Thread.sleep(3000);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new StageResult("failed", "dispatch interrupted");
        } catch (Exception e) {
            log.error("ALE dispatch failed", e);
            return new StageResult("failed", e.getMessage());
        }
    }

    /** 按 stage 读 <run_dir>/<stage>.log 末尾 N 行。 */
    public List<String> tailLog(Path runDir, String stage, int lines) {
        Path logPath = runDir.resolve(stage + ".log");
        if (!Files.exists(logPath)) return List.of();
        try {
            List<String> all = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(lines, 1));
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (Exception e) {
            return List.of("failed to read log: " + e.getMessage());
        }
    }

    private void writeProgress(Path path, String stage, String phase, int percent, String message) throws Exception {
        JSONObject o = new JSONObject();
        o.put("stage", stage);
        o.put("phase", phase);
        o.put("percent", percent);
        if (message != null) o.put("message", message);
        Files.writeString(path, JSON.toJSONString(o), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private JSONObject readProgress(Path path) {
        try {
            if (!Files.exists(path)) return null;
            return JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;  // 损坏/缺失 → 视为无终态，继续轮询
        }
    }
}
```

- [ ] **Step 4: 跑确认通过** — `mvn -pl fly-agent-service -am test -Dtest=AleExecutionGatewayTest` → 4 PASS
  > 若 `timeout=0` 用例抖动，可把 `Math.max(0, timeoutMinutes)` 的轮询首次 sleep 调整为：deadline=now 时仍读一帧（实现已保证：循环先读后判 deadline）。

- [ ] **Step 5: Commit** — `feat(ale): add AleExecutionGateway (atomic trigger, progress reset, poll terminal, timeout, tailLog)`

---

## Task 3: AleStage1Service 接入 gateway + exact contract + 删旧

**Files:**
- Modify: `fly-agent-service/.../ale/AleStage1Service.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AleStage1ServiceTest.java`

> 删除：`buildCommand`、`runCodex`、`estimateProgress`、`logSize`、`hasGeneratedTasks`、`hasOracleEvidence`、`codexConfigPath`、`addToml*` 系列、`updateProgress`（gateway 接管）。保留：`createTasks`、`parseOracleResults`、`applyOracleResults`、`findOracleResult`（改 exact-only）、`mapOracleStatus`、`updateTaskCounts`、`updateTaskCountsToFailed`、`hasFailedTasks`、`allTasksBlocked`、`markTasksStatus`、`markTaskStatus`、`writeSummaryIfMissing`、`toRunDTO` 等 DTO 映射、`buildRunKey`、`resolveTargetCount`、`runDirectory`、`frameworkRoot`、`toJson`。

- [ ] **Step 1: 写失败测试（executeRun 调 gateway 传 exact tasks + 解析 Oracle）**

```java
package com.fly.agent.service.ale;

import com.fly.agent.common.dto.ale.AleRunRequest;
import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.entity.ale.AleTaskEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AleStage1ServiceTest {

    private AleRunRequest request() {
        AleRunRequest r = new AleRunRequest();
        r.setDomain("computing_math");
        r.setDiscipline("software-engineering");
        r.setScenario("task-authoring");
        r.setDifficulty("easy");
        r.setInputMode("brief");
        r.setOutputMode("task-package");
        r.setVerificationMode("oracle");
        r.setReferenceStrategy("hidden-reference");
        r.setTargetCount(1);
        r.setCodexModel("gpt-5.5");
        return r;
    }

    @Test
    void startRunDispatchesGatewayWithExactTasksContract() throws Exception {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleProperties props = new AleProperties();
        props.setOutputRoot(System.getProperty("java.io.tmpdir") + "/ale-test-" + System.nanoTime());

        when(runMapper.insert(any())).thenAnswer(inv -> {
            ((AleRunEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });
        when(gateway.dispatchAndWait(eq(42L), any(), any(), any()))
                .thenReturn(new AleExecutionGateway.StageResult("done", "ok"));

        AleStage1Service svc = new AleStage1Service(runMapper, taskMapper, gateway, props);
        svc.startRun(request());

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gateway).dispatchAndWait(eq(42L), any(), payloadCaptor.capture(), any());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("stage1", payload.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stage1 = (Map<String, Object>) payload.get("stage1");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> tasks = (java.util.List<Map<String, Object>>) stage1.get("tasks");
        assertEquals(1, tasks.size());
        assertEquals("computing_math/task_authoring_01", tasks.get(0).get("task_id"));
    }
}
```

- [ ] **Step 2: 跑确认失败** — FAIL（构造函数签名变了 / gateway 字段不存在）

- [ ] **Step 3: 改造 AleStage1Service**

  - **构造函数**：注入 `AleExecutionGateway`：
    ```java
    private final AleExecutionGateway gateway;
    // @RequiredArgsConstructor 会自动包含（Lombok 按字段顺序）。把 gateway 加为 final 字段，排在 properties 前/后均可。
    ```
  - **删除方法**：`buildCommand`、`runCodex`、`estimateProgress`、`logSize`、`hasGeneratedTasks`、`hasOracleEvidence`、`codexConfigPath`、`addTomlKeyValueModel`、`addTomlQuotedKeyModel`、`addTomlString`、`updateProgress`、`codexModelOptions`（getOptions 改 Task 4）。
  - **`findOracleResult` 改 exact-only**（删 suffix 匹配，只 `taskId.equals`）：
    ```java
    private OracleTaskResult findOracleResult(List<OracleTaskResult> results, String taskId) {
        for (OracleTaskResult r : results) {
            if (taskId.equals(r.taskId())) return r;
        }
        return null;
    }
    ```
  - **`executeRun` 重写**（删 codex 直调，改 gateway）：
    ```java
    private void executeRun(Long runId, AleRunRequest request, Path runDir) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) return;
        try {
            updateRun(runId, AleRunStatus.RUNNING, 10, null);
            Files.writeString(runDir.resolve("request.json"), toJson(request));

            List<AleTaskEntity> tasks = createTasks(runId, request);
            updateTaskSummary(runId, tasks);
            markTasksStatus(runId, "RUNNING", null);

            // 构造 exact task 契约
            List<Map<String, String>> taskContract = tasks.stream()
                    .map(t -> Map.of("task_id", t.getTaskId(), "title",
                            t.getTitle() == null ? "" : t.getTitle()))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "stage1");
            payload.put("run_id", runId);
            payload.put("run_dir", runDir.toString());
            Map<String, Object> stage1 = new LinkedHashMap<>();
            stage1.put("framework_root", frameworkRoot().toString());
            stage1.put("codex_model", request.getCodexModel());
            stage1.put("tasks", taskContract);
            stage1.put("request", toJsonMap(request));
            payload.put("stage1", stage1);

            AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                    runId, runDir, payload, percent -> updateProgressPercent(runId, percent));

            List<OracleTaskResult> oracleResults = parseOracleResults(runDir, tasks);
            applyOracleResults(runId, oracleResults);
            updateTaskCounts(runId, oracleResults);

            if (result.isDone() && !hasFailedTasks(oracleResults)) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "COMPLETED", null);
                updateRun(runId, AleRunStatus.COMPLETED, 100, null);
            } else if (result.isDone() && allTasksBlocked(oracleResults)) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "BLOCKED", "all tasks blocked");
                updateRun(runId, AleRunStatus.BLOCKED, 100, "all tasks blocked");
            } else if (result.isDone()) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "COMPLETED", "some tasks failed/blocked");
                updateRun(runId, AleRunStatus.COMPLETED, 100, "some tasks failed/blocked");
            } else {
                markTasksStatus(runId, "FAILED", result.message());
                writeSummaryIfMissing(runDir, runId, request, tasks, "FAILED", result.message());
                updateRun(runId, AleRunStatus.FAILED, 100, result.message());
            }
        } catch (Exception e) {
            log.error("ALE stage1 run failed", e);
            markTasksStatus(runId, "FAILED", e.getMessage());
            updateTaskCountsToFailed(runId);
            try { writeSummaryIfMissing(runDir, runId, request, List.of(), "FAILED", e.getMessage()); }
            catch (Exception ignored) {}
            updateRun(runId, AleRunStatus.FAILED, 100, e.getMessage());
        }
    }

    private void updateProgressPercent(Long runId, int percent) {
        AleRunEntity u = new AleRunEntity();
        u.setId(runId);
        u.setProgressPercent(percent);
        runMapper.updateById(u);
    }

    private Map<String, Object> toJsonMap(AleRunRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", r.getDomain());
        m.put("discipline", r.getDiscipline());
        m.put("scenario", r.getScenario());
        m.put("difficulty", r.getDifficulty());
        m.put("input_mode", r.getInputMode());
        m.put("output_mode", r.getOutputMode());
        m.put("verification_mode", r.getVerificationMode());
        m.put("reference_strategy", r.getReferenceStrategy());
        m.put("target_count", r.getTargetCount());
        m.put("codex_model", r.getCodexModel());
        return m;
    }
    ```
  - **`tailLog`** 改委托：`return gateway.tailLog(Path.of(run.getOutputRoot()), "stage1", lines);`
  - **`startRun`**：`logPath` 不再设值（删 `run.setLogPath(...)` 那行），保留 `outputRoot`/`summaryPath`。
  - 保留 `EXECUTOR.submit(() -> executeRun(...))` 异步入口。

- [ ] **Step 4: 跑确认通过** — `mvn -pl fly-agent-service -am test -Dtest=AleStage1ServiceTest` → PASS
- [ ] **Step 5: Commit** — `refactor(ale): AleStage1Service via gateway + exact task contract + exact-only oracle match`

---

## Task 4: AleStage1Service getOptions 配置化 + 删 toml 解析

**Files:**
- Modify: `fly-agent-service/.../ale/AleStage1Service.java`
- Test: 追加到 `AleStage1ServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void getOptionsReturnsConfiguredCodexModels() {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleProperties props = new AleProperties();
        props.setCodexModels(java.util.List.of("gpt-5.5", "glm-5"));
        AleStage1Service svc = new AleStage1Service(runMapper, taskMapper, gateway, props);

        List<String> modelValues = svc.getOptions().getCodexModels().stream()
                .map(com.fly.agent.common.dto.ale.AleOptionDTO::getValue).toList();
        assertTrue(modelValues.contains("gpt-5.5"));
        assertTrue(modelValues.contains("glm-5"));
    }
```

- [ ] **Step 2: 跑确认失败** — 若 getOptions 仍读 toml 则不受 props 控制（或编译失败因方法已删）

- [ ] **Step 3: 改 getOptions/codexModelOptions**

```java
    private List<AleOptionDTO> codexModelOptions() {
        return properties.getCodexModels().stream()
                .map(m -> new AleOptionDTO(m, m))
                .toList();
    }
```
> `getOptions` 其余（domains/disciplines/...）不变，仍调用 `codexModelOptions()`。删除 `codexConfigPath` 及 `addToml*`（Task 3 已列删除，若残留在此清掉）。

- [ ] **Step 4: 跑确认通过** — PASS（2 tests in AleStage1ServiceTest）
- [ ] **Step 5: Commit** — `refactor(ale): getOptions reads codex models from config`

---

## Task 5: AleStage2Service 接入 gateway + 删旧

**Files:**
- Modify: `fly-agent-service/.../ale/AleStage2Service.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/ale/AleStage2ServiceTest.java`

> 删除：`findRunnerScript`、`estimateStage2Progress`、常量 `STAGE2_QUEUE_DIR`。保留：状态前置校验、`parseAndApplyResults`、`updateStage2Progress`、`stringField`/`doubleField`、DTO 映射。

- [ ] **Step 1: 写失败测试**

```java
package com.fly.agent.service.ale;

import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AleStage2ServiceTest {

    @Test
    void startStage2DispatchesGatewayWithStage2Payload() {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleProperties props = new AleProperties();

        AleRunEntity run = new AleRunEntity();
        run.setId(7L);
        run.setStatus("COMPLETED");
        run.setStage2Status(null);
        run.setOutputRoot("/tmp/run-7");
        when(runMapper.selectById(7L)).thenReturn(run);
        when(gateway.dispatchAndWait(eq(7L), any(), any(), any()))
                .thenReturn(new AleExecutionGateway.StageResult("done", "ok"));

        AleStage2Service svc = new AleStage2Service(runMapper, taskMapper, gateway, props);
        svc.startStage2(7L);

        verify(gateway).dispatchAndWait(eq(7L), any(), any(), any());
    }

    @Test
    void startStage2RejectsWhenStage1NotCompleted() {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleRunEntity run = new AleRunEntity();
        run.setStatus("RUNNING");
        when(runMapper.selectById(1L)).thenReturn(run);

        AleStage2Service svc = new AleStage2Service(runMapper, taskMapper, gateway, new AleProperties());
        assertThrows(IllegalStateException.class, () -> svc.startStage2(1L));
        verifyNoInteractions(gateway);
    }
}
```
（import `org.junit.jupiter.api.function.Executable` 或用 `assertThrows` 静态导入）

- [ ] **Step 2: 跑确认失败** — FAIL（构造函数变 / STAGE2_QUEUE_DIR 仍在用）

- [ ] **Step 3: 改造 AleStage2Service**

  - 构造函数注入 `AleExecutionGateway` + `AleProperties`（删 `STAGE2_QUEUE_DIR` 常量）。
  - **`executeStage2` 重写**：
    ```java
    private void executeStage2(Long runId) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) return;
        Path runDir = Path.of(run.getOutputRoot());
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "stage2");
            payload.put("run_id", runId);
            payload.put("run_dir", runDir.toString());
            Map<String, Object> s2 = new java.util.LinkedHashMap<>();
            s2.put("framework_root", properties.getFrameworkRoot());
            s2.put("agent", "claude_code");
            s2.put("model", "claude-sonnet-4-6");
            s2.put("timeout", 7200);
            payload.put("stage2", s2);

            AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                    runId, runDir, payload, p -> updateStage2Progress(runId, p));

            Path summaryPath = runDir.resolve("stage2_summary.json");
            if (Files.exists(summaryPath)) {
                parseAndApplyResults(runId, runDir, summaryPath);
            }

            AleRunEntity update = new AleRunEntity();
            update.setId(runId);
            update.setStage2Status(result.isDone() ? "COMPLETED" : "FAILED");
            update.setStage2Progress(100);
            update.setStage2FinishedAt(LocalDateTime.now());
            update.setStage2SummaryPath(summaryPath.toString());
            if (result.isFailed()) update.setErrorMessage(result.message());
            runMapper.updateById(update);
        } catch (Exception e) {
            log.error("Stage2 execution failed", e);
            AleRunEntity update = new AleRunEntity();
            update.setId(runId);
            update.setStage2Status("FAILED");
            update.setStage2Progress(100);
            update.setStage2FinishedAt(LocalDateTime.now());
            runMapper.updateById(update);
        }
    }
    ```
  - **`tailLog`** 委托：`return gateway.tailLog(Path.of(run.getOutputRoot()), "stage2", lines);`
  - 删 `findRunnerScript`、`estimateStage2Progress`。

- [ ] **Step 4: 跑确认通过** — `mvn -pl fly-agent-service -am test -Dtest=AleStage2ServiceTest` → 2 PASS
- [ ] **Step 5: Commit** — `refactor(ale): AleStage2Service via gateway, drop findRunnerScript/estimateStage2Progress/STAGE2_QUEUE_DIR`

---

## Task 6: 配置文件

**Files:**
- Modify: `fly-agent-server/src/main/resources/application.yml`
- Modify: `fly-agent-server/src/main/resources/application-dev.yml`

- [ ] **Step 1: 更新 `ale:` 段**（两文件一致）

```yaml
ale:
  codex-binary: ${ALE_CODEX_BINARY:codex}
  output-root: ${ALE_OUTPUT_ROOT:/data/fly-agent/ale-runs}
  framework-root: ${ALE_FRAMEWORK_ROOT:/home/ubuntu/agents-last-exam}
  queue-dir: ${ALE_QUEUE_DIR:/data/fly-agent/ale-runs/.queue}
  codex-models: ${ALE_CODEX_MODELS:gpt-5.5,gpt-5-mini,gpt-5-codex}
  stage1-timeout-minutes: ${ALE_STAGE1_TIMEOUT_MIN:90}
  stage2-timeout-minutes: ${ALE_STAGE2_TIMEOUT_MIN:240}
```

- [ ] **Step 2: 校验绑定** — `mvn -pl fly-agent-server -am package -DskipTests`（确认 Spring 配置绑定无歧义；`codex-models` List 绑定逗号分隔需 Spring relaxed binding 支持，若不识别改为 YAML list 形式）
- [ ] **Step 3: 全量编译 + 测试** — `mvn -pl fly-agent-service -am test` → 全 PASS（含 Plan 1 的 python 测试不受影响；Java 侧 ale + 既有 swe 测试不回归）
- [ ] **Step 4: Commit** — `chore(ale): wire ale.* config (queue-dir, codex-models, timeouts, absolute output-root)`

---

## Self-Review

**Spec 覆盖：**
- §6.1 AleExecutionGateway（原子写触发文件、重置 progress、轮询 done/failed、超时、tailLog）→ Task 2 ✓
- §6.1 AleStage1Service（删 buildCommand/runCodex/estimateProgress/toml；executeRun 调 gateway；createTasks 产 task contract；findOracleResult exact-only；getOptions 配置化）→ Task 3/4 ✓
- §6.1 AleStage2Service（删 findRunnerScript/estimateStage2Progress/STAGE2_QUEUE_DIR；executeStage2 调 gateway；tailLog 委托）→ Task 5 ✓
- §6.2 DB 不加列（路径由 output_root 推导）→ 全程未加 migration ✓
- §6.6 配置（queue-dir/codex-models/timeouts/绝对 output-root）→ Task 1/6 ✓
- §8 gateway 只认 done/failed + 超时（不做"删除即结束"推测）→ Task 2 测试覆盖 ✓

**占位符扫描：** 无 TBD；每步含真实 Java/YAML/测试代码。

**类型一致性：**
- `AleExecutionGateway.StageResult(phase, message)` —— Task 2 定义，Task 3/5 调用 `isDone()/isFailed()/message()` 一致 ✓
- `dispatchAndWait(Long, Path, Map<String,Object>, IntConsumer)` —— Task 2/3/5 一致 ✓
- `tailLog(Path, String, int)` —— Task 2 定义，Task 3/5 委托一致 ✓
- 构造函数：两 Service 均 `(runMapper, taskMapper, gateway, properties)` —— Task 3/5 测试一致 ✓

**Plan 3 遗留（显式登记，不静默）：** daemon（ale_daemon.sh）、Dockerfile 删 codex、compose mount、部署文档、端到端 —— 属 Plan 3。

---

## Execution Handoff

Plan complete and saved. 执行方式延续 Plan 1 的 Subagent-Driven（每 Task 派 fresh subagent + spec/quality review）。Java 测试用 `mvn -pl fly-agent-service -am test -Dtest=<Class>`。
