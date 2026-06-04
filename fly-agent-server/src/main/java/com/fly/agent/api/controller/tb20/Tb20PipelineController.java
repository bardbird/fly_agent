package com.fly.agent.api.controller.tb20;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.tb20.Tb20BlueprintResponse;
import com.fly.agent.common.dto.tb20.Tb20DependencyStatusDTO;
import com.fly.agent.common.dto.tb20.Tb20InspectRequest;
import com.fly.agent.common.dto.tb20.Tb20PipelineResponse;
import com.fly.agent.service.tb20.Tb20PipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/blueprint")
    public Result<Tb20BlueprintResponse> blueprint(
            @RequestParam(value = "harborRoot", required = false) String harborRoot,
            @RequestParam(value = "terminalBenchRoot", required = false) String terminalBenchRoot) {
        return Result.ok(tb20PipelineService.blueprint(harborRoot, terminalBenchRoot));
    }

    @GetMapping("/dependencies/check")
    public Result<List<Tb20DependencyStatusDTO>> checkDependencies(
            @RequestParam(value = "harborRoot", required = false) String harborRoot,
            @RequestParam(value = "terminalBenchRoot", required = false) String terminalBenchRoot) {
        return Result.ok(tb20PipelineService.checkDependencies(harborRoot, terminalBenchRoot));
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
}
