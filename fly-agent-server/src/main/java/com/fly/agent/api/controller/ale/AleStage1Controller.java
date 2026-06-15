package com.fly.agent.api.controller.ale;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.ale.AleOptionsResponse;
import com.fly.agent.common.dto.ale.AleRunDTO;
import com.fly.agent.common.dto.ale.AleRunRequest;
import com.fly.agent.common.dto.ale.AleRunSummaryDTO;
import com.fly.agent.service.ale.AleStage1Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/ale/stage1")
@RequiredArgsConstructor
public class AleStage1Controller {

    private final AleStage1Service aleStage1Service;

    @GetMapping("/options")
    public Result<AleOptionsResponse> options() {
        return Result.ok(aleStage1Service.getOptions());
    }

    @PostMapping("/runs")
    public Result<AleRunDTO> start(@Valid @RequestBody AleRunRequest request) {
        return Result.ok(aleStage1Service.startRun(request));
    }

    @GetMapping("/runs")
    public Result<List<AleRunSummaryDTO>> runs() {
        return Result.ok(aleStage1Service.listSummaries());
    }

    @GetMapping("/runs/detail")
    public Result<AleRunDTO> detail(@RequestParam("id") Long id) {
        return Result.ok(aleStage1Service.getRun(id));
    }

    @GetMapping("/runs/log")
    public Result<List<String>> log(@RequestParam("id") Long id,
                                    @RequestParam(value = "lines", required = false) Integer lines) {
        return Result.ok(aleStage1Service.tailLog(id, lines == null ? 400 : lines));
    }
}
