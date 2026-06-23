package com.fly.agent.api.controller.ale;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.ale.AleClaudeCodeConfigDTO;
import com.fly.agent.common.dto.ale.AleClaudeCodeConfigRequest;
import com.fly.agent.common.dto.ale.AleRunDTO;
import com.fly.agent.common.dto.ale.AleRunSummaryDTO;
import com.fly.agent.common.dto.ale.AleStage2ReviewDTO;
import com.fly.agent.service.ale.AleStage2Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/ale/stage2")
@RequiredArgsConstructor
public class AleStage2Controller {

    private final AleStage2Service aleStage2Service;

    @GetMapping("/config")
    public Result<AleClaudeCodeConfigDTO> config() {
        return Result.ok(aleStage2Service.getClaudeCodeConfig());
    }

    @PostMapping("/config")
    public Result<AleClaudeCodeConfigDTO> saveConfig(@RequestBody AleClaudeCodeConfigRequest request) {
        return Result.ok(aleStage2Service.saveClaudeCodeConfig(request));
    }

    /** Start stage-2 execution for a completed stage-1 run. */
    @PostMapping("/runs/{id}/start")
    public Result<AleRunDTO> start(@PathVariable("id") Long runId) {
        return Result.ok(aleStage2Service.startStage2(runId));
    }

    @PostMapping("/runs/{id}/tasks/{taskId}/start")
    public Result<AleRunDTO> startTask(@PathVariable("id") Long runId,
                                       @PathVariable("taskId") Long taskId) {
        return Result.ok(aleStage2Service.startStage2Task(runId, taskId));
    }

    @GetMapping("/runs")
    public Result<List<AleRunSummaryDTO>> runs() {
        return Result.ok(aleStage2Service.listSummaries());
    }

    @GetMapping("/runs/detail")
    public Result<AleRunDTO> detail(@RequestParam("id") Long id) {
        return Result.ok(aleStage2Service.getRun(id));
    }

    @GetMapping("/runs/log")
    public Result<List<String>> log(@RequestParam("id") Long id,
                                     @RequestParam(value = "lines", required = false) Integer lines) {
        return Result.ok(aleStage2Service.tailLog(id, lines == null ? 400 : lines));
    }

    @GetMapping("/runs/{id}/agent-log")
    public Result<List<String>> agentLog(@PathVariable("id") Long id,
                                         @RequestParam(value = "lines", required = false) Integer lines) {
        return Result.ok(aleStage2Service.tailAgentLog(id, lines == null ? 400 : lines));
    }

    @GetMapping("/runs/{id}/review")
    public Result<AleStage2ReviewDTO> review(@PathVariable("id") Long id) {
        return Result.ok(aleStage2Service.reviewStage2(id));
    }

    @GetMapping("/runs/{id}/tasks/{taskId}/review")
    public Result<?> reviewTask(@PathVariable("id") Long id,
                                @PathVariable("taskId") Long taskId) {
        return Result.ok(aleStage2Service.reviewStage2Task(id, taskId));
    }

    @GetMapping(value = "/runs/{id}/artifacts.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadArtifacts(@PathVariable("id") Long id) {
        byte[] body = aleStage2Service.buildArtifactsZip(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ale-stage2-run-" + id + "-artifacts.zip")
                        .build()
                .toString())
                .body(body);
    }

    @GetMapping(value = "/runs/{id}/tasks/{taskId}/artifacts.zip", produces = "application/zip")
    public ResponseEntity<byte[]> downloadTaskArtifacts(@PathVariable("id") Long id,
                                                        @PathVariable("taskId") Long taskId) {
        byte[] body = aleStage2Service.buildTaskArtifactsZip(id, taskId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ale-stage2-run-" + id + "-task-" + taskId + "-artifacts.zip")
                        .build()
                        .toString())
                .body(body);
    }
}
