package com.fly.agent.api.controller.tb20;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.tb20.Tb20BlueprintResponse;
import com.fly.agent.common.dto.tb20.Tb20ConfigRequest;
import com.fly.agent.common.dto.tb20.Tb20ConfigResponse;
import com.fly.agent.common.dto.tb20.Tb20DatasetRunRequest;
import com.fly.agent.common.dto.tb20.Tb20DependencyStatusDTO;
import com.fly.agent.common.dto.tb20.Tb20ExecutionRunRequest;
import com.fly.agent.common.dto.tb20.Tb20InspectRequest;
import com.fly.agent.common.dto.tb20.Tb20PipelineResponse;
import com.fly.agent.common.dto.tb20.Tb20RunQueryRequest;
import com.fly.agent.common.dto.tb20.Tb20RunResponse;
import com.fly.agent.service.tb20.Tb20ConfigService;
import com.fly.agent.service.tb20.Tb20PipelineService;
import com.fly.agent.service.tb20.Tb20RunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Terminal-Bench 2.0 production pipeline APIs.
 */
@RestController
@RequestMapping("/api/v1/tb20")
@RequiredArgsConstructor
public class Tb20PipelineController {

    private final Tb20PipelineService tb20PipelineService;
    private final Tb20ConfigService tb20ConfigService;
    private final Tb20RunService tb20RunService;

    @GetMapping("/blueprint")
    public Result<Tb20BlueprintResponse> blueprint() {
        return Result.ok(tb20PipelineService.blueprint());
    }

    @GetMapping("/dependencies/check")
    public Result<List<Tb20DependencyStatusDTO>> checkDependencies() {
        return Result.ok(tb20PipelineService.checkDependencies());
    }

    @PostMapping("/inspect")
    public Result<Tb20PipelineResponse> inspect(@Valid @RequestBody Tb20InspectRequest request) {
        return Result.ok(tb20PipelineService.inspect(request));
    }

    @PostMapping("/runs/single")
    public Result<Tb20PipelineResponse> runSingle(@Valid @RequestBody Tb20InspectRequest request) {
        return Result.ok(tb20PipelineService.runSingle(request));
    }

    @PostMapping("/runs/batch")
    public Result<Tb20PipelineResponse> runBatch(@Valid @RequestBody Tb20InspectRequest request) {
        return Result.ok(tb20PipelineService.runBatch(request));
    }

    @PostMapping("/config/get")
    public Result<Tb20ConfigResponse> getConfig(@Valid @RequestBody Tb20ConfigRequest request) {
        return Result.ok(tb20ConfigService.get(request));
    }

    @PostMapping("/config/save")
    public Result<Tb20ConfigResponse> saveConfig(@Valid @RequestBody Tb20ConfigRequest request) {
        return Result.ok(tb20ConfigService.save(request));
    }

    @PostMapping("/dataset-runs/start")
    public Result<Tb20RunResponse> startDatasetRun(@Valid @RequestBody Tb20DatasetRunRequest request) {
        return Result.ok(tb20RunService.startDatasetRun(request));
    }

    @PostMapping("/execution-runs/start")
    public Result<Tb20RunResponse> startExecutionRun(@Valid @RequestBody Tb20ExecutionRunRequest request) {
        return Result.ok(tb20RunService.startExecutionRun(request));
    }

    @PostMapping("/runs/list")
    public Result<List<Tb20RunResponse>> listRuns() {
        return Result.ok(tb20RunService.listRuns());
    }

    @PostMapping("/runs/detail")
    public Result<Tb20RunResponse> getRun(@Valid @RequestBody Tb20RunQueryRequest request) {
        return Result.ok(tb20RunService.getRun(request.getRunId()));
    }

    @PostMapping("/runs/log")
    public Result<String> getRunLog(@Valid @RequestBody Tb20RunQueryRequest request) {
        return Result.ok(tb20RunService.readLog(request.getRunId()));
    }
}
