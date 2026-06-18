package com.fly.agent.api.controller.ale;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.ale.AleRunDTO;
import com.fly.agent.common.dto.ale.AleRunSummaryDTO;
import com.fly.agent.service.ale.AleStage2Service;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /** Start stage-2 execution for a completed stage-1 run. */
    @PostMapping("/runs/{id}/start")
    public Result<AleRunDTO> start(@PathVariable("id") Long runId) {
        return Result.ok(aleStage2Service.startStage2(runId));
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
}
