package com.fly.agent.api.controller.swe;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.swe.GithubPullScanResponse;
import com.fly.agent.common.dto.swe.GithubPullCandidateListResponse;
import com.fly.agent.common.dto.swe.GithubRepositorySearchRequest;
import com.fly.agent.common.dto.swe.GithubRepositorySearchResponse;
import com.fly.agent.common.dto.swe.SwePipelineRunDTO;
import com.fly.agent.common.dto.swe.SwePipelineStartRequest;
import com.fly.agent.common.dto.swe.SweRuntimeSettingsRequest;
import com.fly.agent.common.dto.swe.SweRuntimeSettingsResponse;
import com.fly.agent.common.dto.swe.SweModelIoConsoleDTO;
import com.fly.agent.common.dto.swe.SweAllowedRepoListResponse;
import com.fly.agent.common.dto.swe.SweScaReportGenerateRequest;
import com.fly.agent.common.dto.swe.SweScaReportGenerateResponse;
import com.fly.agent.common.dto.swe.SweTaskCreateRequest;
import com.fly.agent.common.dto.swe.SweTaskDTO;
import com.fly.agent.common.dto.swe.SweTaskFromCandidateRequest;
import com.fly.agent.service.swe.GithubPullCandidateService;
import com.fly.agent.service.swe.GithubRepositorySearchService;
import com.fly.agent.service.swe.SwePipelineService;
import com.fly.agent.service.swe.SweRuntimeSettingsService;
import com.fly.agent.service.swe.SweScaReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SWE-Pro data production pipeline APIs.
 *
 * <p>Backend routing rule: request parameters are passed by query string or
 * request body, not by path parameters.</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/swe")
@RequiredArgsConstructor
public class SwePipelineController {

    private final SwePipelineService swePipelineService;
    private final SweScaReportService sweScaReportService;
    private final GithubRepositorySearchService githubRepositorySearchService;
    private final GithubPullCandidateService githubPullCandidateService;
    private final SweRuntimeSettingsService sweRuntimeSettingsService;

    @GetMapping("/settings")
    public Result<SweRuntimeSettingsResponse> getSettings() {
        return Result.ok(sweRuntimeSettingsService.getSettings());
    }

    @PostMapping("/settings")
    public Result<SweRuntimeSettingsResponse> saveSettings(@RequestBody SweRuntimeSettingsRequest request) {
        return Result.ok(sweRuntimeSettingsService.saveSettings(request));
    }

    @GetMapping("/github/repositories/search")
    public Result<GithubRepositorySearchResponse> searchGithubRepositories(
            @NotNull(message = "language不能为空") @RequestParam("language") String language,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "minStars", required = false) Integer minStars,
            @RequestParam(value = "maxStars", required = false) Integer maxStars,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "perPage", required = false) Integer perPage,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "order", required = false) String order) {
        GithubRepositorySearchRequest request = new GithubRepositorySearchRequest();
        request.setLanguage(language);
        request.setKeyword(keyword);
        request.setMinStars(minStars);
        request.setMaxStars(maxStars);
        request.setPage(page);
        request.setPerPage(perPage);
        request.setSort(sort);
        request.setOrder(order);
        return Result.ok(githubRepositorySearchService.search(request));
    }

    @GetMapping("/github/pulls/merged-candidates")
    public Result<GithubPullScanResponse> scanMergedPullCandidates(
            @NotNull(message = "repo不能为空") @RequestParam("repo") String repo,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "minGoldSourceFiles", required = false) Integer minGoldSourceFiles,
            @RequestParam(value = "maxGoldSourceFiles", required = false) Integer maxGoldSourceFiles,
            @RequestParam(value = "minGoldLines", required = false) Integer minGoldLines,
            @RequestParam(value = "maxGoldLines", required = false) Integer maxGoldLines,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "perPage", required = false) Integer perPage) {
        return Result.ok(githubPullCandidateService.scanMergedPulls(
                repo,
                limit,
                days,
                minGoldSourceFiles,
                maxGoldSourceFiles,
                minGoldLines,
                maxGoldLines,
                page,
                perPage));
    }

    @GetMapping("/github/pulls/candidate-by-issue")
    public Result<GithubPullScanResponse> findPullCandidateByIssue(
            @NotNull(message = "issueUrl不能为空") @RequestParam("issueUrl") String issueUrl,
            @RequestParam(value = "minGoldSourceFiles", required = false) Integer minGoldSourceFiles,
            @RequestParam(value = "maxGoldSourceFiles", required = false) Integer maxGoldSourceFiles,
            @RequestParam(value = "minGoldLines", required = false) Integer minGoldLines,
            @RequestParam(value = "maxGoldLines", required = false) Integer maxGoldLines) {
        return Result.ok(githubPullCandidateService.findCandidateByIssueUrl(
                issueUrl,
                minGoldSourceFiles,
                maxGoldSourceFiles,
                minGoldLines,
                maxGoldLines));
    }

    @GetMapping("/candidates")
    public Result<GithubPullCandidateListResponse> listCandidates(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "perPage", required = false) Integer perPage,
            @RequestParam(value = "candidateStatus", required = false) String candidateStatus,
            @RequestParam(value = "duplicateStatus", required = false) String duplicateStatus) {
        return Result.ok(githubPullCandidateService.listCandidates(page, perPage, candidateStatus, duplicateStatus));
    }

    @PostMapping("/tasks")
    public Result<SweTaskDTO> createTask(@Valid @RequestBody SweTaskCreateRequest request) {
        return Result.ok(swePipelineService.createTask(request));
    }

    @PostMapping("/tasks/from-candidate")
    public Result<SweTaskDTO> createTaskFromCandidate(@Valid @RequestBody SweTaskFromCandidateRequest request) {
        return Result.ok(swePipelineService.createTaskFromCandidate(request));
    }

    @GetMapping("/tasks")
    public Result<List<SweTaskDTO>> listTasks() {
        return Result.ok(swePipelineService.listTasks());
    }

    @GetMapping("/tasks/detail")
    public Result<SweTaskDTO> getTask(@NotNull(message = "id不能为空") @RequestParam("id") Long id) {
        return Result.ok(swePipelineService.getTask(id));
    }

    @PostMapping("/runs/start")
    public Result<SwePipelineRunDTO> startRun(@Valid @RequestBody SwePipelineStartRequest request) {
        return Result.ok(swePipelineService.startRun(request));
    }

    @PostMapping("/sca-report/generate")
    public Result<SweScaReportGenerateResponse> generateScaReport(
            @Valid @RequestBody SweScaReportGenerateRequest request) {
        return Result.ok(sweScaReportService.generate(request));
    }

    @GetMapping("/sca-report/allowed-repos")
    public Result<SweAllowedRepoListResponse> listAllowedScaRepos(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "perPage", required = false) Integer perPage,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "inCandidate", required = false) Boolean inCandidate,
            @RequestParam(value = "checkedFrom", required = false) String checkedFrom,
            @RequestParam(value = "checkedTo", required = false) String checkedTo) {
        return Result.ok(sweScaReportService.listAllowedRepos(
                page,
                perPage,
                language,
                inCandidate,
                checkedFrom,
                checkedTo));
    }

    @GetMapping(value = "/sca-report/allowed-repos/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportAllowedScaRepos(
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "inCandidate", required = false) Boolean inCandidate,
            @RequestParam(value = "checkedFrom", required = false) String checkedFrom,
            @RequestParam(value = "checkedTo", required = false) String checkedTo) {
        String csv = sweScaReportService.exportAllowedRepoCsv(language, inCandidate, checkedFrom, checkedTo);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("swe_sca_allowed_repos.csv")
                        .build()
                        .toString())
                .body(csv);
    }

    @GetMapping("/runs")
    public Result<List<SwePipelineRunDTO>> listRuns(@RequestParam(value = "taskId", required = false) Long taskId) {
        return Result.ok(swePipelineService.listRuns(taskId));
    }

    @GetMapping("/runs/detail")
    public Result<SwePipelineRunDTO> getRun(@NotNull(message = "runId不能为空") @RequestParam("runId") Long runId) {
        return Result.ok(swePipelineService.getRun(runId));
    }

    @GetMapping("/runs/model-io")
    public Result<SweModelIoConsoleDTO> getModelIoConsole(
            @NotNull(message = "runId不能为空") @RequestParam("runId") Long runId) {
        return Result.ok(swePipelineService.getModelIoConsole(runId));
    }
}
