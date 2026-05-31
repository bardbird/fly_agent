package com.fly.agent.task.job;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.swe.GithubPullScanResponse;
import com.fly.agent.common.dto.swe.GithubRepositoryDTO;
import com.fly.agent.common.dto.swe.GithubRepositorySearchRequest;
import com.fly.agent.common.dto.swe.GithubRepositorySearchResponse;
import com.fly.agent.dao.mapper.swe.SweCandidateMapper;
import com.fly.agent.service.swe.GithubPullCandidateService;
import com.fly.agent.service.swe.GithubRepositorySearchService;
import com.fly.agent.service.swe.GithubTokenContext;
import com.fly.agent.service.swe.SweRepoPrecheckService;
import com.fly.agent.task.service.GithubRepoProfileService;
import com.fly.agent.task.service.SweRepoBlacklistService;
import com.fly.agent.task.service.SweRepoScanCursorService;
import com.fly.agent.task.service.SweRepoScaService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled SWE repo discovery and candidate scan jobs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SweRepoDiscoveryJob {

    private static final List<String> DEFAULT_LANGUAGES = List.of(
            "c",
            "c++",
            "ruby",
            "rust",
            "go",
            "javascript",
            "php",
            "typescript",
            "python",
            "java");
    private static final int SCA_SEARCH_PER_PAGE = 50;
    private static final int MAX_REPOSITORY_SEARCH_PAGES = 10;

    private final GithubRepositorySearchService repositorySearchService;
    private final GithubPullCandidateService pullCandidateService;
    private final SweRepoBlacklistService repoBlacklistService;
    private final SweRepoScanCursorService scanCursorService;
    private final SweRepoScaService repoScaService;
    private final GithubRepoProfileService repoProfileService;
    private final SweRepoPrecheckService repoPrecheckService;
    private final SweCandidateMapper candidateMapper;

    /**
     * Imports the repo blacklist from the Excel file.
     *
     * <p>Job param accepts empty input or JSON such as
     * {@code {"path":"~/Downloads/swe_existing_dataset_blacklist.xlsx"}}.</p>
     */
    @XxlJob("sweRepoBlacklistImportJob")
    public void sweRepoBlacklistImportJob() {
        try {
            String message = importBlacklist(XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess(message);
        } catch (Exception e) {
            log.error("SWE repo blacklist import job failed", e);
            XxlJobHelper.handleFail("SWE repo blacklist import job failed: " + e.getMessage());
        }
    }

    /**
     * Searches GitHub repositories by language/star range, filters blacklisted
     * repos, then scans allowed repos for merged PR candidates.
     */
    @XxlJob("sweRepoDiscoveryScanJob")
    public void sweRepoDiscoveryScanJob() {
        try {
            String message = scanRepositories(XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess(message);
        } catch (Exception e) {
            log.error("SWE repo discovery scan job failed", e);
            XxlJobHelper.handleFail("SWE repo discovery scan job failed: " + e.getMessage());
        }
    }

    /**
     * Scans candidate PRs from repos already accepted by the SCA/license gate.
     *
     * <p>This job does not run GitHub repository search. It reads
     * {@code swe_repo_sca_report.compatibility_status = 'ALLOW'}, excludes
     * blacklisted repos and repos that already have candidates, then scans a
     * fixed number of target repos for merged PR candidates.</p>
     */
    @XxlJob("sweScaAllowRepoCandidateScanJob")
    public void sweScaAllowRepoCandidateScanJob() {
        try {
            String message = scanScaAllowedRepositories(XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess(message);
        } catch (Exception e) {
            log.error("SWE SCA-allowed repo candidate scan job failed", e);
            XxlJobHelper.handleFail("SWE SCA-allowed repo candidate scan job failed: " + e.getMessage());
        }
    }

    /**
     * Searches repositories by language/star conditions and records SCA
     * reports only. Idempotent by scan scope and star cursor.
     */
    @XxlJob("sweRepoScaDiscoveryJob")
    public void sweRepoScaDiscoveryJob() {
        try {
            String message = scanRepositoriesToSca(XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess(message);
        } catch (Exception e) {
            log.error("SWE repo SCA discovery job failed", e);
            XxlJobHelper.handleFail("SWE repo SCA discovery job failed: " + e.getMessage());
        }
    }

    /**
     * Tops up issue-grounded PR candidates from repos already accepted by SCA.
     * Each repo is capped by pullPagesPerRepo to avoid unbounded scans.
     */
    @XxlJob("sweRepoCandidateBackfillJob")
    public void sweRepoCandidateBackfillJob() {
        try {
            String message = backfillCandidatesFromSca(XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess(message);
        } catch (Exception e) {
            log.error("SWE repo candidate backfill job failed", e);
            XxlJobHelper.handleFail("SWE repo candidate backfill job failed: " + e.getMessage());
        }
    }

    public String importBlacklist(String param) {
        String path = parseImportPath(param);
        SweRepoBlacklistService.BlacklistImportResult result = repoBlacklistService.importFromExcel(path);
        String message = "SWE repo blacklist imported, rows=" + result.getImportedRows()
                + ", skipped=" + result.getSkippedRows()
                + ", path=" + result.getPath();
        log.info(message);
        return message;
    }

    public String scanRepositories(String param) {
        ScanRequest request = parseScanRequest(param);
        requireGithubToken(request);
        return GithubTokenContext.withToken(request.getGithubToken(), () -> scanRepositories(request));
    }

    private String scanRepositories(ScanRequest request) {
        if (request.isImportBlacklist()) {
            repoBlacklistService.importFromExcel(request.getBlacklistPath());
        } else {
            repoBlacklistService.initializeSchema();
        }
        scanCursorService.initializeSchema();
        repoScaService.initializeSchema();

        ScanSummary summary = new ScanSummary();
        summary.setLanguages(request.resolveLanguages());
        for (String language : request.resolveLanguages()) {
            scanLanguage(language, request, summary);
        }

        String message = JSON.toJSONString(summary);
        log.info("SWE repo discovery scan finished: {}", message);
        return message;
    }

    public String scanScaAllowedRepositories(String param) {
        ScanRequest request = parseScanRequest(param);
        requireGithubToken(request);
        return GithubTokenContext.withToken(request.getGithubToken(), () -> scanScaAllowedRepositories(request));
    }

    private String scanScaAllowedRepositories(ScanRequest request) {
        if (request.isImportBlacklist()) {
            repoBlacklistService.importFromExcel(request.getBlacklistPath());
        } else {
            repoBlacklistService.initializeSchema();
        }
        repoScaService.initializeSchema();

        List<String> repos = repoScaService.listAllowedReposForCandidateScan(request.getRepoLimit(), request.getRepoOffset());
        LanguageScanSummary languageSummary = new LanguageScanSummary();
        languageSummary.setLanguage("sca_allow_pool");

        AtomicInteger attemptedRepos = new AtomicInteger(0);
        ExecutorService repositoryExecutor = request.getRepositoryConcurrency() <= 1
                ? null
                : Executors.newFixedThreadPool(request.getRepositoryConcurrency());
        try {
            List<RepoScanOutcome> outcomes = processScaAllowedRepositories(repos, request, attemptedRepos, repositoryExecutor);
            for (RepoScanOutcome outcome : outcomes) {
                languageSummary.add(outcome);
            }
        } finally {
            shutdownRepositoryExecutor(repositoryExecutor);
        }

        ScanSummary summary = new ScanSummary();
        summary.setLanguages(List.of("sca_allow_pool"));
        summary.add(languageSummary);
        String message = JSON.toJSONString(summary);
        log.info("SWE SCA-allowed repo candidate scan finished: {}", message);
        return message;
    }

    public String scanRepositoriesToSca(String param) {
        ScanRequest request = parseScanRequest(param);
        requireGithubToken(request);
        return GithubTokenContext.withToken(request.getGithubToken(), () -> scanRepositoriesToSca(request));
    }

    private String scanRepositoriesToSca(ScanRequest request) {
        if (request.isImportBlacklist()) {
            repoBlacklistService.importFromExcel(request.getBlacklistPath());
        } else {
            repoBlacklistService.initializeSchema();
        }
        scanCursorService.initializeSchema();
        repoScaService.initializeSchema();

        ScanSummary summary = new ScanSummary();
        summary.setLanguages(request.resolveLanguages());
        for (String language : request.resolveLanguages()) {
            scanLanguageToSca(language, request, summary);
        }

        String message = JSON.toJSONString(summary);
        log.info("SWE repo SCA discovery finished: {}", message);
        return message;
    }

    public String backfillCandidatesFromSca(String param) {
        ScanRequest request = parseScanRequest(param);
        requireGithubToken(request);
        return GithubTokenContext.withToken(request.getGithubToken(), () -> backfillCandidatesFromSca(request));
    }

    private String backfillCandidatesFromSca(ScanRequest request) {
        if (request.isImportBlacklist()) {
            repoBlacklistService.importFromExcel(request.getBlacklistPath());
        } else {
            repoBlacklistService.initializeSchema();
        }
        repoScaService.initializeSchema();

        ScanSummary summary = new ScanSummary();
        summary.setLanguages(request.resolveLanguages());
        for (String language : request.resolveLanguages()) {
            scanScaCandidatesForLanguage(language, request, summary);
        }

        String message = JSON.toJSONString(summary);
        log.info("SWE repo candidate backfill finished: {}", message);
        return message;
    }

    private void scanLanguage(String language, ScanRequest request, ScanSummary summary) {
        Integer initialMaxStars = request.getMaxStars();
        if (request.getStartStars() != null) {
            initialMaxStars = request.getStartStars();
        }
        if (request.isResetCursor()) {
            scanCursorService.reset(language, request.getKeyword(), request.getMinStars(), initialMaxStars);
        }

        SweRepoScanCursorService.ScanCursor cursor = scanCursorService.getOrCreate(
                language,
                request.getKeyword(),
                request.getMinStars(),
                initialMaxStars);
        if (request.isUseStarCursor() && cursor.isExhausted()) {
            summary.getLanguageSummaries().add(skippedSummary(language, request, initialMaxStars, "star_range_exhausted"));
            return;
        }

        Integer searchMaxStars = request.isUseStarCursor() ? cursor.getCurrentMaxStars() : initialMaxStars;
        if (searchMaxStars != null && searchMaxStars < request.getMinStars()) {
            summary.getLanguageSummaries().add(skippedSummary(language, request, searchMaxStars, "star_range_exhausted"));
            return;
        }

        LanguageScanSummary languageSummary = new LanguageScanSummary();
        languageSummary.setLanguage(language);
        languageSummary.setMinStars(request.getMinStars());
        languageSummary.setMaxStars(searchMaxStars);
        languageSummary.setCursorKey(cursor.getCursorKey());

        Integer minSeenStars = null;
        AtomicInteger scannedReposForLanguage = new AtomicInteger(0);
        int pageStart = request.isUseStarCursor() ? 1 : request.getPage();
        ExecutorService repositoryExecutor = request.getRepositoryConcurrency() <= 1
                ? null
                : Executors.newFixedThreadPool(request.getRepositoryConcurrency());
        try {
            for (int pageOffset = 0; pageOffset < request.getRepositoryPages(); pageOffset++) {
                if (scannedReposForLanguage.get() >= request.getRepoLimit()) {
                    break;
                }
                int page = pageStart + pageOffset;
                GithubRepositorySearchResponse response = repositorySearchService.search(searchRequest(
                        language,
                        request,
                        searchMaxStars,
                        page));
                List<GithubRepositoryDTO> repositories = response.getRepositories() == null
                        ? List.of()
                        : response.getRepositories();
                if (repositories.isEmpty()) {
                    continue;
                }
                List<RepoScanOutcome> outcomes = processRepositories(
                        repositories,
                        request,
                        scannedReposForLanguage,
                        repositoryExecutor);
                for (RepoScanOutcome outcome : outcomes) {
                    minSeenStars = minStar(minSeenStars, outcome.getMinSeenStars());
                    languageSummary.add(outcome);
                }
            }
        } finally {
            shutdownRepositoryExecutor(repositoryExecutor);
        }

        if (request.isUseStarCursor()) {
            scanCursorService.advance(
                    language,
                    request.getKeyword(),
                    request.getMinStars(),
                    initialMaxStars,
                    minSeenStars,
                    "found=" + languageSummary.getFoundRepos()
                            + ", attempted=" + languageSummary.getAttemptedRepos()
                            + ", blacklisted=" + languageSummary.getBlacklistedRepos()
                            + ", profileRejected=" + languageSummary.getProfileRejectedRepos()
                            + ", scaRejected=" + languageSummary.getScaRejectedRepos()
                            + ", scanned=" + languageSummary.getScannedRepos()
                            + ", skippedByFilter=" + languageSummary.getSkippedByFilter()
                            + ", candidates=" + languageSummary.getCandidates());
            languageSummary.setNextMaxStars(minSeenStars == null ? null : minSeenStars - 1);
        }
        summary.add(languageSummary);
    }

    private void scanLanguageToSca(String language, ScanRequest request, ScanSummary summary) {
        Integer initialMaxStars = request.getMaxStars();
        if (request.getStartStars() != null) {
            initialMaxStars = request.getStartStars();
        }
        int existingScaReports = repoScaService.countReposInScanScope(
                language,
                request.getKeyword(),
                request.getMinStars(),
                initialMaxStars);
        int scaReportsToday = repoScaService.countReposCheckedOnDateInScanScope(
                language,
                request.getKeyword(),
                request.getMinStars(),
                initialMaxStars,
                request.getScanDate());
        int remainingDailyRepoLimit = Math.max(request.getRepoLimit() - scaReportsToday, 0);
        int remainingRepoLimit = request.resolvePerRunRepoLimit(remainingDailyRepoLimit);
        if (request.isResetCursor()) {
            scanCursorService.reset(language, request.getKeyword(), request.getMinStars(), initialMaxStars);
        }

        SweRepoScanCursorService.ScanCursor cursor = scanCursorService.getOrCreate(
                language,
                request.getKeyword(),
                request.getMinStars(),
                initialMaxStars);
        if (request.isUseStarCursor() && cursor.isExhausted()) {
            summary.add(skippedSummary(language, request, initialMaxStars, "star_range_exhausted"));
            return;
        }

        Integer searchMaxStars = request.isUseStarCursor() ? cursor.getCurrentMaxStars() : initialMaxStars;
        if (searchMaxStars != null && searchMaxStars < request.getMinStars()) {
            summary.add(skippedSummary(language, request, searchMaxStars, "star_range_exhausted"));
            return;
        }

        LanguageScanSummary languageSummary = new LanguageScanSummary();
        languageSummary.setLanguage(language);
        languageSummary.setMinStars(request.getMinStars());
        languageSummary.setMaxStars(searchMaxStars);
        languageSummary.setCursorKey(cursor.getCursorKey());
        languageSummary.setExistingScaReports(existingScaReports);
        languageSummary.setDailyRepoLimit(request.getRepoLimit());
        languageSummary.setPerRunRepoLimit(request.getPerRunRepoLimit());
        languageSummary.setReposProcessedToday(scaReportsToday);
        languageSummary.setScanDate(request.getScanDate().toString());
        if (remainingRepoLimit <= 0) {
            languageSummary.setSkippedReason("daily_repo_limit_reached");
            summary.add(languageSummary);
            return;
        }

        Integer minSeenStars = null;
        int processedRepos = 0;
        int repositoryPages = scaRepositoryPages(remainingRepoLimit);
        for (int pageOffset = 0; pageOffset < repositoryPages
                && processedRepos < remainingRepoLimit; pageOffset++) {
            int page = (request.isUseStarCursor() ? 1 : request.getPage()) + pageOffset;
            GithubRepositorySearchResponse response = repositorySearchService.search(searchRequest(
                    language,
                    request,
                    searchMaxStars,
                    page,
                    SCA_SEARCH_PER_PAGE));
            List<GithubRepositoryDTO> repositories = response.getRepositories() == null
                    ? List.of()
                    : response.getRepositories();
            if (repositories.isEmpty()) {
                continue;
            }
            for (GithubRepositoryDTO repository : repositories) {
                if (processedRepos >= remainingRepoLimit) {
                    break;
                }
                processedRepos++;
                RepoScanOutcome outcome = processRepositoryToSca(
                        repository,
                        language,
                        request,
                        initialMaxStars);
                minSeenStars = minStar(minSeenStars, outcome.getMinSeenStars());
                languageSummary.add(outcome);
            }
        }

        if (request.isUseStarCursor()) {
            scanCursorService.advance(
                    language,
                    request.getKeyword(),
                    request.getMinStars(),
                    initialMaxStars,
                    minSeenStars,
                    "scaOnly found=" + languageSummary.getFoundRepos()
                            + ", blacklisted=" + languageSummary.getBlacklistedRepos()
                            + ", profileRejected=" + languageSummary.getProfileRejectedRepos()
                            + ", scaRejected=" + languageSummary.getScaRejectedRepos()
                            + ", scaAllowed=" + languageSummary.getScaAllowedRepos()
                            + ", scaChecked=" + languageSummary.getScaCheckedRepos());
            languageSummary.setNextMaxStars(minSeenStars == null ? null : minSeenStars - 1);
        }
        summary.add(languageSummary);
    }

    private void scanScaCandidatesForLanguage(String language, ScanRequest request, ScanSummary summary) {
        int scannedToday = repoScaService.countCandidateScannedOnDateInScanScope(
                language,
                request.getKeyword(),
                request.getMinStars(),
                request.getMaxStars(),
                request.getScanDate());
        int remainingDailyRepoLimit = Math.max(request.getRepoLimit() - scannedToday, 0);
        int remainingRepoLimit = request.resolvePerRunRepoLimit(remainingDailyRepoLimit);
        List<String> repos = repoScaService.listAllowedReposForCandidateScan(
                language,
                request.getKeyword(),
                request.getMinStars(),
                request.getMaxStars(),
                request.getScanDate(),
                remainingRepoLimit,
                request.getRepoOffset());
        LanguageScanSummary languageSummary = new LanguageScanSummary();
        languageSummary.setLanguage(language);
        languageSummary.setMinStars(request.getMinStars());
        languageSummary.setMaxStars(request.getMaxStars());
        languageSummary.setDailyRepoLimit(request.getRepoLimit());
        languageSummary.setPerRunRepoLimit(request.getPerRunRepoLimit());
        languageSummary.setReposProcessedToday(scannedToday);
        languageSummary.setScanDate(request.getScanDate().toString());
        if (remainingRepoLimit <= 0) {
            languageSummary.setSkippedReason("daily_repo_limit_reached");
            summary.add(languageSummary);
            return;
        }

        AtomicInteger attemptedRepos = new AtomicInteger(0);
        ExecutorService repositoryExecutor = request.getRepositoryConcurrency() <= 1
                ? null
                : Executors.newFixedThreadPool(request.getRepositoryConcurrency());
        try {
            List<RepoScanOutcome> outcomes = processCandidateBackfillRepositories(
                    repos,
                    request,
                    attemptedRepos,
                    repositoryExecutor);
            for (RepoScanOutcome outcome : outcomes) {
                languageSummary.add(outcome);
            }
        } finally {
            shutdownRepositoryExecutor(repositoryExecutor);
        }
        summary.add(languageSummary);
    }

    private List<RepoScanOutcome> processRepositories(
            List<GithubRepositoryDTO> repositories,
            ScanRequest request,
            AtomicInteger scannedReposForLanguage,
            ExecutorService repositoryExecutor) {
        if (repositoryExecutor == null) {
            List<RepoScanOutcome> outcomes = new ArrayList<>();
            for (GithubRepositoryDTO repository : repositories) {
                outcomes.add(processRepository(repository, request, scannedReposForLanguage));
            }
            return outcomes;
        }
        List<Future<RepoScanOutcome>> futures = new ArrayList<>();
        for (GithubRepositoryDTO repository : repositories) {
            Callable<RepoScanOutcome> task = () -> GithubTokenContext.withToken(
                    request.getGithubToken(),
                    () -> processRepository(repository, request, scannedReposForLanguage));
            futures.add(repositoryExecutor.submit(task));
        }
        List<RepoScanOutcome> outcomes = new ArrayList<>();
        for (Future<RepoScanOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SWE repo discovery scan interrupted", e);
            } catch (ExecutionException e) {
                RepoScanOutcome outcome = new RepoScanOutcome();
                outcome.setFailedRepos(1);
                log.warn("Failed to process SWE repo scan task", e.getCause());
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    private List<RepoScanOutcome> processScaAllowedRepositories(
            List<String> repos,
            ScanRequest request,
            AtomicInteger attemptedRepos,
            ExecutorService repositoryExecutor) {
        if (repositoryExecutor == null) {
            List<RepoScanOutcome> outcomes = new ArrayList<>();
            for (String repo : repos) {
                outcomes.add(processScaAllowedRepository(repo, request, attemptedRepos));
            }
            return outcomes;
        }
        List<Future<RepoScanOutcome>> futures = new ArrayList<>();
        for (String repo : repos) {
            Callable<RepoScanOutcome> task = () -> GithubTokenContext.withToken(
                    request.getGithubToken(),
                    () -> processScaAllowedRepository(repo, request, attemptedRepos));
            futures.add(repositoryExecutor.submit(task));
        }
        List<RepoScanOutcome> outcomes = new ArrayList<>();
        for (Future<RepoScanOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SWE SCA-allowed repo candidate scan interrupted", e);
            } catch (ExecutionException e) {
                RepoScanOutcome outcome = new RepoScanOutcome();
                outcome.setFailedRepos(1);
                log.warn("Failed to process SCA-allowed repo scan task", e.getCause());
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    private List<RepoScanOutcome> processCandidateBackfillRepositories(
            List<String> repos,
            ScanRequest request,
            AtomicInteger attemptedRepos,
            ExecutorService repositoryExecutor) {
        if (repositoryExecutor == null) {
            List<RepoScanOutcome> outcomes = new ArrayList<>();
            for (String repo : repos) {
                outcomes.add(processCandidateBackfillRepository(repo, request, attemptedRepos));
            }
            return outcomes;
        }
        List<Future<RepoScanOutcome>> futures = new ArrayList<>();
        for (String repo : repos) {
            Callable<RepoScanOutcome> task = () -> GithubTokenContext.withToken(
                    request.getGithubToken(),
                    () -> processCandidateBackfillRepository(repo, request, attemptedRepos));
            futures.add(repositoryExecutor.submit(task));
        }
        List<RepoScanOutcome> outcomes = new ArrayList<>();
        for (Future<RepoScanOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SWE candidate backfill scan interrupted", e);
            } catch (ExecutionException e) {
                RepoScanOutcome outcome = new RepoScanOutcome();
                outcome.setFailedRepos(1);
                log.warn("Failed to process SWE candidate backfill task", e.getCause());
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    private RepoScanOutcome processCandidateBackfillRepository(
            String repo,
            ScanRequest request,
            AtomicInteger attemptedRepos) {
        RepoScanOutcome outcome = new RepoScanOutcome();
        outcome.setFoundRepos(1);
        int existingCandidates = candidateMapper.countQualifiedIssueCandidatesByRepo(repo);
        outcome.setExistingCandidates(existingCandidates);
        if (existingCandidates >= request.getPullLimit()) {
            outcome.setSkippedEnoughRepos(1);
            return outcome;
        }
        SweRepoPrecheckService.RepoPrecheckDecision precheck = repoPrecheckService.check(repo);
        if (!precheck.allowed() && "repo_blacklisted".equals(precheck.reasonCode())) {
            outcome.setBlacklistedRepos(1);
            return outcome;
        }
        if (!precheck.allowed()) {
            outcome.setScaRejectedRepos(1);
            return outcome;
        }
        outcome.setScaAllowedRepos(1);
        if (!tryAcquireRepoScanSlot(attemptedRepos, request.getRepoLimit())) {
            return outcome;
        }
        outcome.setAttemptedRepos(1);
        repoScaService.markCandidateScanAttempt(repo);
        return scanRepo(repo, request, outcome, request.getPullLimit() - existingCandidates);
    }

    private RepoScanOutcome processScaAllowedRepository(String repo, ScanRequest request, AtomicInteger attemptedRepos) {
        RepoScanOutcome outcome = new RepoScanOutcome();
        outcome.setFoundRepos(1);
        SweRepoPrecheckService.RepoPrecheckDecision precheck = repoPrecheckService.check(repo);
        if (!precheck.allowed() && "repo_blacklisted".equals(precheck.reasonCode())) {
            outcome.setBlacklistedRepos(1);
            return outcome;
        }
        if (!precheck.allowed()) {
            outcome.setScaRejectedRepos(1);
            log.info("Repo rejected by SCA-allowed pool precheck, repo={}, reasonCode={}, reason={}",
                    repo,
                    precheck.reasonCode(),
                    precheck.reason());
            return outcome;
        }
        outcome.setScaAllowedRepos(1);
        if (!tryAcquireRepoScanSlot(attemptedRepos, request.getRepoLimit())) {
            return outcome;
        }
        outcome.setAttemptedRepos(1);
        return scanRepo(repo, request, outcome);
    }

    private RepoScanOutcome processRepositoryToSca(
            GithubRepositoryDTO repository,
            String language,
            ScanRequest request,
            Integer searchMaxStars) {
        RepoScanOutcome outcome = new RepoScanOutcome();
        String repo = repository.getFullName();
        outcome.setFoundRepos(1);
        outcome.setMinSeenStars(repository.getStargazersCount());

        SweRepoPrecheckService.RepoPrecheckDecision precheck = repoPrecheckService.check(repo);
        if (!precheck.allowed() && "repo_blacklisted".equals(precheck.reasonCode())) {
            outcome.setBlacklistedRepos(1);
            return outcome;
        }
        if (!precheck.allowed() && !"sca_license_rejected".equals(precheck.reasonCode())) {
            outcome.setScaRejectedRepos(1);
            return outcome;
        }
        GithubRepoProfileService.RepoProfileDecision profileDecision = analyzeProfile(repository, request, outcome);
        if (!profileDecision.isAllowed()) {
            outcome.setProfileRejectedRepos(1);
            log.info("Repo rejected by profile filter, repo={}, reasonCode={}, reason={}",
                    repo,
                    profileDecision.getReasonCode(),
                    profileDecision.getReason());
            return outcome;
        }
        SweRepoScaService.LicensePrecheckDecision licensePrecheck = SweRepoScaService.precheckLicense(
                repository.getLicenseSpdxId(),
                repository.getLicenseName());
        if (!licensePrecheck.allowed()) {
            repoScaService.analyzeRepo(
                    repository,
                    request.getKeyword(),
                    request.getMinStars(),
                    searchMaxStars,
                    profileDecision.toJson());
            outcome.setScaCheckedRepos(1);
            outcome.setScaRejectedRepos(1);
            return outcome;
        }
        SweRepoScaService.ScaDecision scaDecision = repoScaService.analyzeRepo(
                repository,
                request.getKeyword(),
                request.getMinStars(),
                searchMaxStars,
                profileDecision.toJson());
        outcome.setScaCheckedRepos(1);
        if (scaDecision.isAllowed()) {
            outcome.setScaAllowedRepos(1);
        } else {
            outcome.setScaRejectedRepos(1);
        }
        return outcome;
    }

    private RepoScanOutcome processRepository(
            GithubRepositoryDTO repository,
            ScanRequest request,
            AtomicInteger scannedReposForLanguage) {
        RepoScanOutcome outcome = new RepoScanOutcome();
        String repo = repository.getFullName();
        outcome.setFoundRepos(1);
        outcome.setMinSeenStars(repository.getStargazersCount());

        SweRepoPrecheckService.RepoPrecheckDecision precheck = repoPrecheckService.check(repo);
        if (!precheck.allowed() && "repo_blacklisted".equals(precheck.reasonCode())) {
            outcome.setBlacklistedRepos(1);
            return outcome;
        }
        if (!precheck.allowed()) {
            outcome.setScaRejectedRepos(1);
            log.info("Repo rejected by existing precheck, repo={}, reasonCode={}, reason={}",
                    repo,
                    precheck.reasonCode(),
                    precheck.reason());
            return outcome;
        }
        GithubRepoProfileService.RepoProfileDecision profileDecision = analyzeProfile(repository, request, outcome);
        if (!profileDecision.isAllowed()) {
            outcome.setProfileRejectedRepos(1);
            log.info("Repo rejected by profile filter, repo={}, reasonCode={}, reason={}",
                    repo,
                    profileDecision.getReasonCode(),
                    profileDecision.getReason());
            return outcome;
        }
        SweRepoScaService.LicensePrecheckDecision licensePrecheck = SweRepoScaService.precheckLicense(
                repository.getLicenseSpdxId(),
                repository.getLicenseName());
        if (!licensePrecheck.allowed()) {
            outcome.setScaRejectedRepos(1);
            log.info("Repo rejected by search license precheck, repo={}, license={}, reason={}",
                    repo,
                    licensePrecheck.spdxId(),
                    licensePrecheck.reason());
            return outcome;
        }
        SweRepoScaService.ScaDecision scaDecision = repoScaService.analyzeRepo(
                repository,
                request.getKeyword(),
                request.getMinStars(),
                request.getMaxStars(),
                profileDecision.toJson());
        outcome.setScaCheckedRepos(1);
        SweRepoPrecheckService.RepoPrecheckDecision postScaPrecheck = repoPrecheckService.check(repo);
        if (!postScaPrecheck.allowed()) {
            outcome.setScaRejectedRepos(1);
            log.info("Repo rejected by SCA license gate, repo={}, license={}, reason={}",
                    repo,
                    scaDecision.getLicenseSpdxId(),
                    postScaPrecheck.reason());
            return outcome;
        }
        outcome.setScaAllowedRepos(1);
        if (!tryAcquireRepoScanSlot(scannedReposForLanguage, request.getRepoLimit())) {
            return outcome;
        }
        outcome.setAttemptedRepos(1);
        return scanRepo(repo, request, outcome);
    }

    private GithubRepoProfileService.RepoProfileDecision analyzeProfile(
            GithubRepositoryDTO repository,
            ScanRequest request,
            RepoScanOutcome outcome) {
        GithubRepoProfileService.ProfileConstraints constraints = new GithubRepoProfileService.ProfileConstraints();
        constraints.setEnabled(request.isProfileFilterEnabled());
        constraints.setMinPrimaryLanguageRatio(request.getMinPrimaryLanguageRatio());
        constraints.setMaxLanguageCount(request.getMaxLanguageCount());
        constraints.setMaxDirectDependencies(request.getMaxDirectDependencies());
        constraints.setMaxManifestCount(request.getMaxManifestCount());
        constraints.setMaxManifestDownloads(request.getMaxManifestDownloads());
        GithubRepoProfileService.RepoProfileDecision decision = repoProfileService.analyze(repository, constraints);
        outcome.setProfileCheckedRepos(1);
        if (decision.isAllowed()) {
            outcome.setProfileAllowedRepos(1);
        }
        return decision;
    }

    private boolean tryAcquireRepoScanSlot(AtomicInteger scannedReposForLanguage, int repoLimit) {
        while (true) {
            int current = scannedReposForLanguage.get();
            if (current >= repoLimit) {
                return false;
            }
            if (scannedReposForLanguage.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private RepoScanOutcome scanRepo(String repo, ScanRequest request, RepoScanOutcome outcome) {
        return scanRepo(repo, request, outcome, request.getPullLimit());
    }

    private RepoScanOutcome scanRepo(String repo, ScanRequest request, RepoScanOutcome outcome, int targetCandidates) {
        if (!StringUtils.hasText(repo)) {
            return outcome;
        }
        int remainingCandidates = Math.max(targetCandidates, 0);
        boolean completedPage = false;
        try {
            for (int pageOffset = 0; pageOffset < request.getPullPagesPerRepo() && remainingCandidates > 0; pageOffset++) {
                GithubPullScanResponse scanResponse = pullCandidateService.scanMergedPulls(
                        repo,
                        remainingCandidates,
                        request.getDays(),
                        request.getMinGoldSourceFiles(),
                        request.getMaxGoldSourceFiles(),
                        request.getMinGoldLines(),
                        request.getMaxGoldLines(),
                        request.getPullPage() + pageOffset,
                        request.getPullPerPage());
                completedPage = true;
                outcome.setScannedRepos(1);
                int candidates = scanResponse.getCandidates().size();
                outcome.setCandidates(outcome.getCandidates() + candidates);
                outcome.setScannedPulls(outcome.getScannedPulls() + nullToZero(scanResponse.getScannedPulls()));
                outcome.setSkippedByFilter(outcome.getSkippedByFilter() + nullToZero(scanResponse.getSkippedByFilter()));
                outcome.setSkippedDelivered(outcome.getSkippedDelivered() + nullToZero(scanResponse.getSkippedDelivered()));
                remainingCandidates -= candidates;
                if (!Boolean.TRUE.equals(scanResponse.getHasMore())) {
                    break;
                }
            }
        } catch (Exception e) {
            if (!completedPage) {
                outcome.setFailedRepos(1);
            }
            log.warn("Failed to scan SWE repo candidates, repo={}", repo, e);
        }
        return outcome;
    }

    private void shutdownRepositoryExecutor(ExecutorService repositoryExecutor) {
        if (repositoryExecutor == null) {
            return;
        }
        repositoryExecutor.shutdown();
        try {
            if (!repositoryExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                repositoryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            repositoryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private GithubRepositorySearchRequest searchRequest(
            String language,
            ScanRequest request,
            Integer searchMaxStars,
            int page) {
        return searchRequest(language, request, searchMaxStars, page, request.getRepositoryPerPage());
    }

    private GithubRepositorySearchRequest searchRequest(
            String language,
            ScanRequest request,
            Integer searchMaxStars,
            int page,
            int perPage) {
        GithubRepositorySearchRequest searchRequest = new GithubRepositorySearchRequest();
        searchRequest.setLanguage(language);
        searchRequest.setKeyword(request.getKeyword());
        searchRequest.setMinStars(request.getMinStars());
        searchRequest.setMaxStars(searchMaxStars);
        searchRequest.setPage(page);
        searchRequest.setPerPage(perPage);
        searchRequest.setSort("stars");
        searchRequest.setOrder("desc");
        searchRequest.setPrecheckFilter(false);
        return searchRequest;
    }

    private int scaRepositoryPages(int remainingRepoLimit) {
        int pagesNeededForLimit = (remainingRepoLimit + SCA_SEARCH_PER_PAGE - 1) / SCA_SEARCH_PER_PAGE;
        return Math.min(Math.max(pagesNeededForLimit, 1), MAX_REPOSITORY_SEARCH_PAGES);
    }

    private LanguageScanSummary skippedSummary(
            String language,
            ScanRequest request,
            Integer maxStars,
            String reason) {
        LanguageScanSummary summary = LanguageScanSummary.skipped(language, reason);
        summary.setMinStars(request.getMinStars());
        summary.setMaxStars(maxStars);
        return summary;
    }

    private Integer minStar(Integer currentMin, Integer stars) {
        if (stars == null) {
            return currentMin;
        }
        if (currentMin == null) {
            return stars;
        }
        return Math.min(currentMin, stars);
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String parseImportPath(String param) {
        if (!StringUtils.hasText(param)) {
            return SweRepoBlacklistService.DEFAULT_BLACKLIST_PATH;
        }
        String trimmed = param.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        String path = JSON.parseObject(trimmed).getString("path");
        return StringUtils.hasText(path) ? path : SweRepoBlacklistService.DEFAULT_BLACKLIST_PATH;
    }

    private ScanRequest parseScanRequest(String param) {
        ScanRequest request = new ScanRequest();
        if (!StringUtils.hasText(param)) {
            return request;
        }
        JSONObject json = JSON.parseObject(param.trim());
        request.setLanguages(parseLanguages(json.get("languages")));
        request.setKeyword(json.getString("keyword"));
        request.setMinStars(intValue(json, "minStars", request.getMinStars(), 0, Integer.MAX_VALUE));
        request.setMaxStars(optionalPositiveInt(json, "maxStars"));
        request.setStartStars(optionalPositiveInt(json, "startStars"));
        request.setPage(intValue(json, "page", request.getPage(), 1, Integer.MAX_VALUE));
        request.setRepositoryPages(intValue(json, "repositoryPages", request.getRepositoryPages(), 1, 10));
        request.setRepositoryPerPage(intValue(json, "repositoryPerPage", request.getRepositoryPerPage(), 1, 50));
        request.setRepositoryConcurrency(intValue(json, "repositoryConcurrency", request.getRepositoryConcurrency(), 1, 5));
        request.setRepoLimit(intValue(json, "repoLimit", request.getRepoLimit(), 1, 1000));
        request.setRepoLimit(intValue(json, "dailyRepoLimit", request.getRepoLimit(), 1, 1000));
        request.setPerRunRepoLimit(optionalPositiveInt(json, "perRunRepoLimit"));
        request.setRepoOffset(intValue(json, "repoOffset", request.getRepoOffset(), 0, Integer.MAX_VALUE));
        request.setPullLimit(intValue(json, "pullLimit", request.getPullLimit(), 1, 50));
        request.setDays(intValue(json, "days", request.getDays(), 1, 3650));
        request.setMinGoldSourceFiles(optionalPositiveInt(json, "minGoldSourceFiles"));
        request.setMaxGoldSourceFiles(optionalPositiveInt(json, "maxGoldSourceFiles"));
        request.setMinGoldLines(optionalPositiveInt(json, "minGoldLines"));
        request.setMaxGoldLines(optionalPositiveInt(json, "maxGoldLines"));
        request.setPullPage(intValue(json, "pullPage", request.getPullPage(), 1, Integer.MAX_VALUE));
        request.setPullPerPage(intValue(json, "pullPerPage", request.getPullPerPage(), 1, 10));
        request.setPullPagesPerRepo(intValue(json, "pullPagesPerRepo", request.getPullPagesPerRepo(), 1, 100));
        request.setProfileFilterEnabled(booleanValue(json, "profileFilterEnabled", request.isProfileFilterEnabled()));
        request.setMinPrimaryLanguageRatio(doubleValue(
                json,
                "minPrimaryLanguageRatio",
                request.getMinPrimaryLanguageRatio(),
                0.0d,
                1.0d));
        request.setMaxLanguageCount(intValue(json, "maxLanguageCount", request.getMaxLanguageCount(), 1, 20));
        request.setMaxDirectDependencies(intValue(json, "maxDirectDependencies", request.getMaxDirectDependencies(), 0, 500));
        request.setMaxManifestCount(intValue(json, "maxManifestCount", request.getMaxManifestCount(), 0, 100));
        request.setMaxManifestDownloads(intValue(json, "maxManifestDownloads", request.getMaxManifestDownloads(), 0, 20));
        request.setUseStarCursor(booleanValue(json, "useStarCursor", request.isUseStarCursor()));
        request.setResetCursor(booleanValue(json, "resetCursor", request.isResetCursor()));
        request.setImportBlacklist(booleanValue(json, "importBlacklist", request.isImportBlacklist()));
        String scanDate = json.getString("scanDate");
        if (StringUtils.hasText(scanDate)) {
            request.setScanDate(LocalDate.parse(scanDate.trim()));
        }
        String githubToken = json.getString("githubToken");
        if (StringUtils.hasText(githubToken)) {
            request.setGithubToken(githubToken.trim());
        }
        String blacklistPath = json.getString("blacklistPath");
        if (StringUtils.hasText(blacklistPath)) {
            request.setBlacklistPath(blacklistPath);
        }
        return request;
    }

    private void requireGithubToken(ScanRequest request) {
        if (!StringUtils.hasText(request.getGithubToken())) {
            throw new IllegalArgumentException("githubToken 为必填任务参数");
        }
    }

    private List<String> parseLanguages(Object value) {
        if (value == null) {
            return DEFAULT_LANGUAGES;
        }
        List<String> languages = new ArrayList<>();
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                addLanguage(languages, item);
            }
        } else {
            for (String item : String.valueOf(value).split(",")) {
                addLanguage(languages, item);
            }
        }
        return languages.isEmpty() ? DEFAULT_LANGUAGES : languages;
    }

    private void addLanguage(List<String> languages, Object value) {
        if (value == null) {
            return;
        }
        String language = normalizeLanguage(String.valueOf(value));
        if (StringUtils.hasText(language) && !languages.contains(language)) {
            languages.add(language);
        }
    }

    private String normalizeLanguage(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String language = value.trim().toLowerCase(Locale.ROOT);
        return switch (language) {
            case "js" -> "javascript";
            case "ts" -> "typescript";
            default -> language;
        };
    }

    private int intValue(JSONObject json, String key, int defaultValue, int minValue, int maxValue) {
        Integer value = json.getInteger(key);
        if (value == null) {
            return defaultValue;
        }
        return Math.min(Math.max(value, minValue), maxValue);
    }

    private Integer optionalPositiveInt(JSONObject json, String key) {
        Integer value = json.getInteger(key);
        return value == null ? null : Math.max(value, 0);
    }

    private boolean booleanValue(JSONObject json, String key, boolean defaultValue) {
        Boolean value = json.getBoolean(key);
        return value == null ? defaultValue : value;
    }

    private double doubleValue(JSONObject json, String key, double defaultValue, double minValue, double maxValue) {
        Double value = json.getDouble(key);
        if (value == null) {
            return defaultValue;
        }
        return Math.min(Math.max(value, minValue), maxValue);
    }

    @Data
    private static class ScanRequest {

        private List<String> languages = DEFAULT_LANGUAGES;

        private String keyword;

        private int minStars = 100;

        private Integer maxStars;

        private Integer startStars;

        private int page = 1;

        private int repositoryPages = 1;

        private int repositoryPerPage = 20;

        private int repositoryConcurrency = 1;

        private int repoLimit = 10;

        private Integer perRunRepoLimit;

        private int repoOffset;

        private int pullLimit = 3;

        private int days = 365;

        private Integer minGoldSourceFiles;

        private Integer maxGoldSourceFiles;

        private Integer minGoldLines;

        private Integer maxGoldLines;

        private int pullPage = 1;

        private int pullPerPage = 5;

        private int pullPagesPerRepo = 1;

        private boolean profileFilterEnabled = true;

        private double minPrimaryLanguageRatio = 0.70d;

        private int maxLanguageCount = 4;

        private int maxDirectDependencies = 30;

        private int maxManifestCount = 8;

        private int maxManifestDownloads = 3;

        private boolean useStarCursor = true;

        private boolean resetCursor;

        private boolean importBlacklist;

        private String blacklistPath = SweRepoBlacklistService.DEFAULT_BLACKLIST_PATH;

        private String githubToken;

        private LocalDate scanDate = LocalDate.now();

        private List<String> resolveLanguages() {
            return languages == null || languages.isEmpty() ? DEFAULT_LANGUAGES : languages;
        }

        private int resolvePerRunRepoLimit(int remainingDailyRepoLimit) {
            if (perRunRepoLimit == null || perRunRepoLimit <= 0) {
                return remainingDailyRepoLimit;
            }
            return Math.min(perRunRepoLimit, remainingDailyRepoLimit);
        }
    }

    @Data
    private static class ScanSummary {

        private List<String> languages = new ArrayList<>();

        private int foundRepos;

        private int attemptedRepos;

        private int blacklistedRepos;

        private int scannedRepos;

        private int failedRepos;

        private int scaCheckedRepos;

        private int scaAllowedRepos;

        private int scaRejectedRepos;

        private int profileCheckedRepos;

        private int profileAllowedRepos;

        private int profileRejectedRepos;

        private int candidates;

        private int scannedPulls;

        private int skippedByFilter;

        private int skippedDelivered;

        private int skippedEnoughRepos;

        private int existingCandidates;

        private int existingScaReports;

        private List<LanguageScanSummary> languageSummaries = new ArrayList<>();

        private void add(LanguageScanSummary languageSummary) {
            languageSummaries.add(languageSummary);
            foundRepos += languageSummary.getFoundRepos();
            attemptedRepos += languageSummary.getAttemptedRepos();
            blacklistedRepos += languageSummary.getBlacklistedRepos();
            scannedRepos += languageSummary.getScannedRepos();
            failedRepos += languageSummary.getFailedRepos();
            scaCheckedRepos += languageSummary.getScaCheckedRepos();
            scaAllowedRepos += languageSummary.getScaAllowedRepos();
            scaRejectedRepos += languageSummary.getScaRejectedRepos();
            profileCheckedRepos += languageSummary.getProfileCheckedRepos();
            profileAllowedRepos += languageSummary.getProfileAllowedRepos();
            profileRejectedRepos += languageSummary.getProfileRejectedRepos();
            candidates += languageSummary.getCandidates();
            scannedPulls += languageSummary.getScannedPulls();
            skippedByFilter += languageSummary.getSkippedByFilter();
            skippedDelivered += languageSummary.getSkippedDelivered();
            skippedEnoughRepos += languageSummary.getSkippedEnoughRepos();
            existingCandidates += languageSummary.getExistingCandidates();
            existingScaReports += languageSummary.getExistingScaReports();
        }
    }

    @Data
    private static class LanguageScanSummary {

        private String language;

        private int minStars;

        private Integer maxStars;

        private Integer nextMaxStars;

        private String cursorKey;

        private String skippedReason;

        private int foundRepos;

        private int attemptedRepos;

        private int blacklistedRepos;

        private int scannedRepos;

        private int failedRepos;

        private int scaCheckedRepos;

        private int scaAllowedRepos;

        private int scaRejectedRepos;

        private int profileCheckedRepos;

        private int profileAllowedRepos;

        private int profileRejectedRepos;

        private int candidates;

        private int scannedPulls;

        private int skippedByFilter;

        private int skippedDelivered;

        private int skippedEnoughRepos;

        private int existingCandidates;

        private int existingScaReports;

        private int dailyRepoLimit;

        private Integer perRunRepoLimit;

        private int reposProcessedToday;

        private String scanDate;

        private void add(RepoScanOutcome outcome) {
            foundRepos += outcome.getFoundRepos();
            attemptedRepos += outcome.getAttemptedRepos();
            blacklistedRepos += outcome.getBlacklistedRepos();
            scannedRepos += outcome.getScannedRepos();
            failedRepos += outcome.getFailedRepos();
            scaCheckedRepos += outcome.getScaCheckedRepos();
            scaAllowedRepos += outcome.getScaAllowedRepos();
            scaRejectedRepos += outcome.getScaRejectedRepos();
            profileCheckedRepos += outcome.getProfileCheckedRepos();
            profileAllowedRepos += outcome.getProfileAllowedRepos();
            profileRejectedRepos += outcome.getProfileRejectedRepos();
            candidates += outcome.getCandidates();
            scannedPulls += outcome.getScannedPulls();
            skippedByFilter += outcome.getSkippedByFilter();
            skippedDelivered += outcome.getSkippedDelivered();
            skippedEnoughRepos += outcome.getSkippedEnoughRepos();
            existingCandidates += outcome.getExistingCandidates();
        }

        private static LanguageScanSummary skipped(String language, String reason) {
            LanguageScanSummary summary = new LanguageScanSummary();
            summary.setLanguage(language);
            summary.setSkippedReason(reason);
            return summary;
        }
    }

    @Data
    private static class RepoScanOutcome {

        private Integer minSeenStars;

        private int foundRepos;

        private int attemptedRepos;

        private int blacklistedRepos;

        private int scannedRepos;

        private int failedRepos;

        private int scaCheckedRepos;

        private int scaAllowedRepos;

        private int scaRejectedRepos;

        private int profileCheckedRepos;

        private int profileAllowedRepos;

        private int profileRejectedRepos;

        private int candidates;

        private int scannedPulls;

        private int skippedByFilter;

        private int skippedDelivered;

        private int skippedEnoughRepos;

        private int existingCandidates;
    }
}
