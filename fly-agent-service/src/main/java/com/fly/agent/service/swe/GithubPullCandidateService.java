package com.fly.agent.service.swe;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.common.dto.swe.GithubPullCandidateDTO;
import com.fly.agent.common.dto.swe.GithubPullCandidateListResponse;
import com.fly.agent.common.dto.swe.GithubPullScanResponse;
import com.fly.agent.common.enums.swe.SwePipelineStatus;
import com.fly.agent.common.exception.BusinessException;
import com.fly.agent.dao.entity.swe.SweCandidateEntity;
import com.fly.agent.dao.entity.swe.SweTaskEntity;
import com.fly.agent.dao.mapper.swe.SweCandidateMapper;
import com.fly.agent.dao.mapper.swe.SweTaskMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers issue-grounded merged PR candidates and scores them with the
 * SWE-Pro gold patch rules.
 *
 * <p>Collection intentionally requires GitHub closing keywords so each retained
 * candidate has a resolved issue, problem statement source, and candidate
 * evidence chain before task-package generation.</p>
 */
@Service
public class GithubPullCandidateService {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final int DEFAULT_DAYS = 365;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_SCAN_BATCH_SIZE = 5;
    private static final int MAX_SCAN_BATCH_SIZE = 10;
    private static final int PR_FILES_PER_PAGE = 30;
    private static final int GITHUB_PROXY_CONNECT_TIMEOUT_MS = 500;
    private static final String GITHUB_PROXY_HOST = "127.0.0.1";
    private static final int GITHUB_PROXY_PORT = 7897;
    private static final int MIN_GOLD_FILES = 5;
    private static final int MIN_GOLD_LINES = 108;
    private static final int DEFAULT_MIN_GOLD_SOURCE_FILES = 5;
    private static final int DEFAULT_MAX_GOLD_SOURCE_FILES = 10;
    private static final int DEFAULT_MAX_GOLD_LINES = 300;
    private static final int MIN_TEST_PATCH_FILES = 1;
    private static final int PREFERRED_GOLD_LINES = 200;
    private static final int STRONG_GOLD_LINES = 300;
    private static final List<String> NON_SOURCE_SUFFIXES = List.of(".md", ".txt", ".json", ".yaml", ".yml", ".lock", ".sum");
    private static final List<String> GENERATED_NEEDLES = List.of("lock", "vendor/", "generated", "dist/", "locale", "i18n", "snapshot");
    private static final Pattern RESOLVED_ISSUE_PATTERN = Pattern.compile(
            "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+(?:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)?#(\\d+)");
    private static final Pattern GITHUB_ISSUE_URL_PATTERN = Pattern.compile(
            "^https?://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/issues/(\\d+)(?:[/?#].*)?$");
    private static final Pattern GITHUB_PULL_URL_PATTERN = Pattern.compile(
            "^https?://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/(\\d+)(?:[/?#].*)?$");
    private static final Map<String, List<String>> LANGUAGE_EXTENSIONS = new LinkedHashMap<>();

    static {
        LANGUAGE_EXTENSIONS.put("python", List.of(".py"));
        LANGUAGE_EXTENSIONS.put("go", List.of(".go"));
        LANGUAGE_EXTENSIONS.put("javascript", List.of(".js", ".jsx"));
        LANGUAGE_EXTENSIONS.put("typescript", List.of(".ts", ".tsx"));
        LANGUAGE_EXTENSIONS.put("java", List.of(".java"));
        LANGUAGE_EXTENSIONS.put("rust", List.of(".rs"));
        LANGUAGE_EXTENSIONS.put("cpp", List.of(".cc", ".cpp", ".cxx", ".h", ".hpp"));
        LANGUAGE_EXTENSIONS.put("c", List.of(".c", ".h"));
        LANGUAGE_EXTENSIONS.put("php", List.of(".php"));
        LANGUAGE_EXTENSIONS.put("ruby", List.of(".rb"));
    }

    private final WebClient webClient;
    private final String githubToken;
    private final SweCandidateMapper candidateMapper;
    private final SweTaskMapper taskMapper;
    private final TransactionTemplate transactionTemplate;
    private final SweRepoPrecheckService repoPrecheckService;
    private final SweRuntimeSettingsService runtimeSettingsService;

    @Autowired
    public GithubPullCandidateService(
            @Value("${swe.github.token:}") String githubToken,
            SweCandidateMapper candidateMapper,
            SweTaskMapper taskMapper,
            PlatformTransactionManager transactionManager,
            SweRepoPrecheckService repoPrecheckService,
            SweRuntimeSettingsService runtimeSettingsService) {
        this.githubToken = githubToken;
        this.candidateMapper = candidateMapper;
        this.taskMapper = taskMapper;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.repoPrecheckService = repoPrecheckService;
        this.runtimeSettingsService = runtimeSettingsService;
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "fly-agent-swe-pro")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024));
        applyProxyIfAvailable(builder);
        this.webClient = builder.build();
    }

    GithubPullCandidateService(
            String githubToken,
            SweCandidateMapper candidateMapper,
            SweTaskMapper taskMapper,
            PlatformTransactionManager transactionManager) {
        this(githubToken, candidateMapper, taskMapper, transactionManager, null, null);
    }

    public GithubPullScanResponse scanMergedPulls(
            String repo,
            Integer limit,
            Integer days,
            Integer minGoldSourceFiles,
            Integer maxGoldSourceFiles,
            Integer minGoldLines,
            Integer maxGoldLines,
            Integer page,
            Integer perPage) {
        String normalizedRepo = normalizeRepo(repo);
        requireRepoPrecheckAllowed(normalizedRepo);
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        int resolvedPage = page == null ? 1 : Math.max(page, 1);
        int resolvedPerPage = perPage == null
                ? DEFAULT_SCAN_BATCH_SIZE
                : Math.min(Math.max(perPage, 1), MAX_SCAN_BATCH_SIZE);
        int resolvedDays = days == null ? DEFAULT_DAYS : Math.max(days, 1);
        int resolvedMinGoldSourceFiles = minGoldSourceFiles == null
                ? DEFAULT_MIN_GOLD_SOURCE_FILES
                : Math.max(minGoldSourceFiles, 0);
        int resolvedMaxGoldSourceFiles = maxGoldSourceFiles == null
                ? DEFAULT_MAX_GOLD_SOURCE_FILES
                : Math.max(maxGoldSourceFiles, 0);
        int resolvedMinGoldLines = minGoldLines == null ? MIN_GOLD_LINES : Math.max(minGoldLines, 0);
        int resolvedMaxGoldLines = maxGoldLines == null ? DEFAULT_MAX_GOLD_LINES : Math.max(maxGoldLines, 0);
        validateRange("源码文件数量", resolvedMinGoldSourceFiles, resolvedMaxGoldSourceFiles);
        validateRange("Gold 行数", resolvedMinGoldLines, resolvedMaxGoldLines);
        OffsetDateTime since = OffsetDateTime.now().minusDays(resolvedDays);

        GithubPullScanResponse response = new GithubPullScanResponse();
        response.setRepo(normalizedRepo);
        response.setLimit(resolvedLimit);
        response.setPage(resolvedPage);
        response.setPerPage(resolvedPerPage);
        response.setNextPage(resolvedPage + 1);
        response.setHasMore(false);
        response.setDays(resolvedDays);
        response.setScannedPulls(0);
        response.setMergedPulls(0);
        response.setSkippedUnmerged(0);
        response.setSkippedOutOfRange(0);
        response.setSkippedByFilter(0);
        response.setSkippedNoResolvedIssue(0);
        response.setSkippedDelivered(0);
        response.setMinGoldSourceFiles(resolvedMinGoldSourceFiles);
        response.setMaxGoldSourceFiles(resolvedMaxGoldSourceFiles);
        response.setMinGoldLines(resolvedMinGoldLines);
        response.setMaxGoldLines(resolvedMaxGoldLines);

        JSONArray pulls = getArray(uri("/repos/" + normalizedRepo + "/pulls")
                + "?state=closed&sort=updated&direction=desc&per_page=" + resolvedPerPage + "&page=" + resolvedPage);
        if (pulls == null || pulls.isEmpty()) {
            response.setHasMore(false);
            return response;
        }

        boolean reachedDateBoundary = false;
        for (Object value : pulls) {
            if (response.getCandidates().size() >= resolvedLimit) {
                break;
            }
            JSONObject pull = toJsonObject(value);
            response.setScannedPulls(response.getScannedPulls() + 1);

            OffsetDateTime updatedAt = parseDate(pull.getString("updated_at"));
            if (updatedAt == null || updatedAt.isBefore(since)) {
                response.setSkippedOutOfRange(response.getSkippedOutOfRange() + 1);
                reachedDateBoundary = true;
                continue;
            }

            Integer number = pull.getInteger("number");
            String mergedAt = pull.getString("merged_at");
            if (!StringUtils.hasText(mergedAt)) {
                response.setSkippedUnmerged(response.getSkippedUnmerged() + 1);
                continue;
            }
            response.setMergedPulls(response.getMergedPulls() + 1);

            List<Integer> issueNumbers = extractResolvedIssueNumbers(pull.getString("title"), pull.getString("body"));
            if (issueNumbers.isEmpty()) {
                response.setSkippedNoResolvedIssue(response.getSkippedNoResolvedIssue() + 1);
                continue;
            }
            IssueContext issueContext = fetchIssueContext(normalizedRepo, issueNumbers, parseDate(pull.getString("created_at")));
            List<JSONObject> files = getPullFiles(normalizedRepo, number);
            GithubPullCandidateDTO candidate = toCandidate(normalizedRepo, pull, files, issueNumbers, issueContext, response.getCandidates().size() + 1);
            if (!matchesFilters(
                    candidate,
                    resolvedMinGoldSourceFiles,
                    resolvedMaxGoldSourceFiles,
                    resolvedMinGoldLines,
                    resolvedMaxGoldLines)) {
                response.setSkippedByFilter(response.getSkippedByFilter() + 1);
                continue;
            }
            boolean delivered = isDelivered(candidate);
            candidate.setDuplicateStatus(delivered ? "DELIVERED" : "NEW");
            SweCandidateEntity persisted = upsertCandidate(candidate);
            candidate.setId(persisted.getId());
            if (delivered) {
                response.setSkippedDelivered(response.getSkippedDelivered() + 1);
                continue;
            }
            response.getCandidates().add(candidate);
        }

        response.setHasMore(pulls.size() == resolvedPerPage && !reachedDateBoundary);
        response.getCandidates().sort(Comparator.comparing(GithubPullCandidateDTO::getScore).reversed());
        for (int i = 0; i < response.getCandidates().size(); i++) {
            response.getCandidates().get(i).setCandidateId(String.format("CAND-%04d", i + 1));
        }
        return response;
    }

    public GithubPullScanResponse findCandidateByIssueUrl(
            String issueUrl,
            Integer minGoldSourceFiles,
            Integer maxGoldSourceFiles,
            Integer minGoldLines,
            Integer maxGoldLines) {
        IssueRef issueRef = parseIssueUrl(issueUrl);
        requireRepoPrecheckAllowed(issueRef.repo());
        int resolvedMinGoldSourceFiles = minGoldSourceFiles == null
                ? DEFAULT_MIN_GOLD_SOURCE_FILES
                : Math.max(minGoldSourceFiles, 0);
        int resolvedMaxGoldSourceFiles = maxGoldSourceFiles == null
                ? DEFAULT_MAX_GOLD_SOURCE_FILES
                : Math.max(maxGoldSourceFiles, 0);
        int resolvedMinGoldLines = minGoldLines == null ? MIN_GOLD_LINES : Math.max(minGoldLines, 0);
        int resolvedMaxGoldLines = maxGoldLines == null ? DEFAULT_MAX_GOLD_LINES : Math.max(maxGoldLines, 0);
        validateRange("源码文件数量", resolvedMinGoldSourceFiles, resolvedMaxGoldSourceFiles);
        validateRange("Gold 行数", resolvedMinGoldLines, resolvedMaxGoldLines);

        GithubPullScanResponse response = new GithubPullScanResponse();
        response.setRepo(issueRef.repo());
        response.setLimit(1);
        response.setPage(1);
        response.setPerPage(1);
        response.setNextPage(1);
        response.setHasMore(false);
        response.setScannedPulls(0);
        response.setMergedPulls(0);
        response.setSkippedUnmerged(0);
        response.setSkippedOutOfRange(0);
        response.setSkippedByFilter(0);
        response.setSkippedNoResolvedIssue(0);
        response.setSkippedDelivered(0);
        response.setMinGoldSourceFiles(resolvedMinGoldSourceFiles);
        response.setMaxGoldSourceFiles(resolvedMaxGoldSourceFiles);
        response.setMinGoldLines(resolvedMinGoldLines);
        response.setMaxGoldLines(resolvedMaxGoldLines);

        Integer pullNumber = findClosingPullNumber(issueRef);
        if (pullNumber == null) {
            response.setSkippedNoResolvedIssue(1);
            return response;
        }

        JSONObject pull = getObject(uri("/repos/" + issueRef.repo() + "/pulls/" + pullNumber));
        if (pull == null || pull.isEmpty()) {
            response.setSkippedNoResolvedIssue(1);
            return response;
        }
        response.setScannedPulls(1);
        if (!StringUtils.hasText(pull.getString("merged_at"))) {
            response.setSkippedUnmerged(1);
            return response;
        }
        response.setMergedPulls(1);
        if (!closesIssue(pull.getString("title"), pull.getString("body"), issueRef.number())) {
            response.setSkippedNoResolvedIssue(1);
            return response;
        }

        List<Integer> issueNumbers = List.of(issueRef.number());
        IssueContext issueContext = fetchIssueContext(issueRef.repo(), issueNumbers, parseDate(pull.getString("created_at")));
        List<JSONObject> files = getPullFiles(issueRef.repo(), pullNumber);
        GithubPullCandidateDTO candidate = toCandidate(issueRef.repo(), pull, files, issueNumbers, issueContext, 1);
        if (!matchesFilters(
                candidate,
                resolvedMinGoldSourceFiles,
                resolvedMaxGoldSourceFiles,
                resolvedMinGoldLines,
                resolvedMaxGoldLines)) {
            response.setSkippedByFilter(1);
            return response;
        }
        boolean delivered = isDelivered(candidate);
        candidate.setDuplicateStatus(delivered ? "DELIVERED" : "NEW");
        SweCandidateEntity persisted = upsertCandidate(candidate);
        candidate.setId(persisted.getId());
        if (delivered) {
            response.setSkippedDelivered(1);
            return response;
        }
        response.getCandidates().add(candidate);
        return response;
    }

    public GithubPullCandidateListResponse listCandidates(
            Integer page,
            Integer perPage,
            String candidateStatus,
            String duplicateStatus) {
        int resolvedPage = page == null ? 1 : Math.max(page, 1);
        int resolvedPerPage = perPage == null ? 10 : Math.min(Math.max(perPage, 1), 50);
        LambdaQueryWrapper<SweCandidateEntity> countWrapper = candidateFilter(candidateStatus, duplicateStatus);
        Long total = candidateMapper.selectCount(countWrapper);
        int totalPages = total == null || total == 0
                ? 1
                : (int) Math.ceil(total / (double) resolvedPerPage);
        int offset = (resolvedPage - 1) * resolvedPerPage;
        LambdaQueryWrapper<SweCandidateEntity> wrapper = candidateFilter(candidateStatus, duplicateStatus)
                .orderByDesc(SweCandidateEntity::getModifiedAt)
                .last("LIMIT " + offset + ", " + resolvedPerPage);
        GithubPullCandidateListResponse response = new GithubPullCandidateListResponse();
        response.setPage(resolvedPage);
        response.setPerPage(resolvedPerPage);
        response.setTotal(total == null ? 0L : total);
        response.setTotalPages(totalPages);
        response.setCandidates(candidateMapper.selectList(wrapper).stream()
                .map(this::toCandidateDTO)
                .toList());
        return response;
    }

    private LambdaQueryWrapper<SweCandidateEntity> candidateFilter(String candidateStatus, String duplicateStatus) {
        LambdaQueryWrapper<SweCandidateEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(candidateStatus)) {
            wrapper.eq(SweCandidateEntity::getCandidateStatus, candidateStatus);
        }
        if (StringUtils.hasText(duplicateStatus)) {
            wrapper.eq(SweCandidateEntity::getDuplicateStatus, duplicateStatus);
        }
        return wrapper;
    }

    private void requireRepoPrecheckAllowed(String repo) {
        if (repoPrecheckService == null) {
            return;
        }
        SweRepoPrecheckService.RepoPrecheckDecision decision = repoPrecheckService.check(repo);
        if (!decision.allowed()) {
            throw new BusinessException("仓库未通过 SWE-Pro 扫描预检: "
                    + decision.reasonCode() + "；" + decision.reason());
        }
    }

    private boolean isDelivered(GithubPullCandidateDTO candidate) {
        if (!StringUtils.hasText(candidate.getPrUrl())) {
            return false;
        }
        Long deliveredTaskCount = taskMapper.selectCount(new LambdaQueryWrapper<SweTaskEntity>()
                .eq(SweTaskEntity::getSourceUrl, candidate.getPrUrl())
                .in(SweTaskEntity::getStatus,
                        SwePipelineStatus.COMPLETED.getCode(),
                        SwePipelineStatus.DELIVERED.getCode()));
        if (deliveredTaskCount != null && deliveredTaskCount > 0) {
            return true;
        }
        SweCandidateEntity existing = candidateMapper.selectOne(new LambdaQueryWrapper<SweCandidateEntity>()
                .eq(SweCandidateEntity::getPrUrl, candidate.getPrUrl())
                .last("LIMIT 1"));
        return existing != null && "DELIVERED".equals(existing.getDuplicateStatus());
    }

    private SweCandidateEntity upsertCandidate(GithubPullCandidateDTO candidate) {
        if (transactionTemplate == null) {
            return upsertCandidateInTransaction(candidate);
        }
        return transactionTemplate.execute(status -> upsertCandidateInTransaction(candidate));
    }

    private SweCandidateEntity upsertCandidateInTransaction(GithubPullCandidateDTO candidate) {
        SweCandidateEntity entity = candidateMapper.selectByPrUrlForUpdate(candidate.getPrUrl());
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new SweCandidateEntity();
            entity.setCreatedAt(now);
            fillCandidateEntity(entity, candidate);
            entity.setModifiedAt(now);
            try {
                candidateMapper.insert(entity);
                return entity;
            } catch (DuplicateKeyException ignored) {
                entity = candidateMapper.selectByPrUrlForUpdate(candidate.getPrUrl());
                if (entity == null) {
                    throw ignored;
                }
            }
        }
        mergeCandidateEntity(entity, candidate, now);
        candidateMapper.updateById(entity);
        return entity;
    }

    private void mergeCandidateEntity(SweCandidateEntity entity, GithubPullCandidateDTO candidate, LocalDateTime now) {
        String previousIssueUrl = entity.getIssueUrl();
        String previousIssueNumbers = entity.getIssueNumbers();
        String previousProblemStatement = entity.getProblemStatement();
        String previousHintsText = entity.getHintsText();

        fillCandidateEntity(entity, candidate);
        entity.setIssueUrl(mergeIssueUrl(previousIssueUrl, candidate.getIssueUrl()));
        entity.setIssueNumbers(mergeJsonIntArrays(previousIssueNumbers, candidate.getIssueNumbers()));
        entity.setProblemStatement(mergeEvidenceText(previousProblemStatement, candidate.getProblemStatement()));
        entity.setHintsText(mergeEvidenceText(previousHintsText, candidate.getHintsText()));
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setModifiedAt(now);
    }

    private String mergeIssueUrl(String existing, String incoming) {
        if (!StringUtils.hasText(existing)) {
            return incoming;
        }
        return existing;
    }

    private String mergeJsonIntArrays(String existing, String incoming) {
        Set<Integer> numbers = new LinkedHashSet<>();
        addJsonIntArray(numbers, existing);
        addJsonIntArray(numbers, incoming);
        return JSON.toJSONString(numbers);
    }

    private void addJsonIntArray(Set<Integer> numbers, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            JSON.parseArray(value, Integer.class).stream()
                    .filter(number -> number != null)
                    .forEach(numbers::add);
        } catch (Exception ignored) {
            Matcher matcher = Pattern.compile("\\d+").matcher(value);
            while (matcher.find()) {
                numbers.add(Integer.parseInt(matcher.group()));
            }
        }
    }

    private String mergeEvidenceText(String existing, String incoming) {
        if (!StringUtils.hasText(existing)) {
            return incoming;
        }
        if (!StringUtils.hasText(incoming) || existing.contains(incoming)) {
            return existing;
        }
        return existing + "\n\n---\n\n" + incoming;
    }

    private void fillCandidateEntity(SweCandidateEntity entity, GithubPullCandidateDTO candidate) {
        entity.setCandidateId(candidate.getCandidateId());
        entity.setRepo(candidate.getRepo());
        entity.setPrNumber(candidate.getNumber());
        entity.setPrUrl(candidate.getPrUrl());
        entity.setIssueUrl(candidate.getIssueUrl());
        entity.setIssueNumbers(candidate.getIssueNumbers());
        entity.setProblemStatement(candidate.getProblemStatement());
        entity.setHintsText(candidate.getHintsText());
        entity.setTitle(candidate.getTitle());
        entity.setBaseCommit(candidate.getBaseCommit());
        entity.setFixCommit(candidate.getFixCommit());
        entity.setMergeCommit(candidate.getMergeCommit());
        entity.setPrimaryLanguage(candidate.getPrimaryLanguage());
        entity.setSecondaryLanguages(candidate.getSecondaryLanguages());
        entity.setPatchFiles(candidate.getPatchFiles());
        entity.setSourceFiles(candidate.getSourceFiles());
        entity.setInsertions(candidate.getInsertions());
        entity.setDeletions(candidate.getDeletions());
        entity.setTotalChanged(candidate.getTotalChanged());
        entity.setGoldPatchFiles(candidate.getGoldPatchFiles());
        entity.setGoldSourceFiles(candidate.getGoldSourceFiles());
        entity.setGoldInsertions(candidate.getGoldInsertions());
        entity.setGoldDeletions(candidate.getGoldDeletions());
        entity.setGoldTotalChanged(candidate.getGoldTotalChanged());
        entity.setTestPatchFiles(candidate.getTestPatchFiles());
        entity.setTestInsertions(candidate.getTestInsertions());
        entity.setTestDeletions(candidate.getTestDeletions());
        entity.setTestTotalChanged(candidate.getTestTotalChanged());
        entity.setTestPatchPresent(candidate.getTestPatchPresent());
        entity.setFailToPass(candidate.getFailToPass());
        entity.setPassToPass(candidate.getPassToPass());
        entity.setBenchmarkStatus(candidate.getBenchmarkStatus());
        entity.setFailedHistoryStatus(candidate.getFailedHistoryStatus());
        entity.setGeneratedOrI18nRatio(candidate.getGeneratedOrI18nRatio());
        entity.setScore(candidate.getScore());
        entity.setCandidateGrade(candidate.getCandidateGrade());
        entity.setGradeReason(candidate.getGradeReason());
        entity.setCandidateStatus(candidate.getCandidateStatus());
        entity.setDuplicateStatus(candidate.getDuplicateStatus());
        entity.setStrengths(JSON.toJSONString(candidate.getStrengths()));
        entity.setRisks(JSON.toJSONString(candidate.getRisks()));
        entity.setSampleFiles(JSON.toJSONString(candidate.getSampleFiles()));
        entity.setRawJson(JSON.toJSONString(candidate));
        entity.setMergedAt(toLocalDateTime(candidate.getMergedAt()));
        entity.setUpdatedAt(toLocalDateTime(candidate.getUpdatedAt()));
    }

    private GithubPullCandidateDTO toCandidateDTO(SweCandidateEntity entity) {
        GithubPullCandidateDTO dto = new GithubPullCandidateDTO();
        dto.setId(entity.getId());
        dto.setCandidateId(entity.getCandidateId());
        dto.setRepo(entity.getRepo());
        dto.setNumber(entity.getPrNumber());
        dto.setTitle(entity.getTitle());
        dto.setPrUrl(entity.getPrUrl());
        dto.setIssueUrl(entity.getIssueUrl());
        dto.setIssueNumbers(entity.getIssueNumbers());
        dto.setProblemStatement(entity.getProblemStatement());
        dto.setHintsText(entity.getHintsText());
        dto.setBaseCommit(entity.getBaseCommit());
        dto.setFixCommit(entity.getFixCommit());
        dto.setMergeCommit(entity.getMergeCommit());
        dto.setMergedAt(entity.getMergedAt() == null ? null : entity.getMergedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
        dto.setPrimaryLanguage(entity.getPrimaryLanguage());
        dto.setSecondaryLanguages(entity.getSecondaryLanguages());
        dto.setPatchFiles(entity.getPatchFiles());
        dto.setSourceFiles(entity.getSourceFiles());
        dto.setInsertions(entity.getInsertions());
        dto.setDeletions(entity.getDeletions());
        dto.setTotalChanged(entity.getTotalChanged());
        dto.setGoldPatchFiles(entity.getGoldPatchFiles());
        dto.setGoldSourceFiles(entity.getGoldSourceFiles());
        dto.setGoldInsertions(entity.getGoldInsertions());
        dto.setGoldDeletions(entity.getGoldDeletions());
        dto.setGoldTotalChanged(entity.getGoldTotalChanged());
        dto.setTestPatchFiles(entity.getTestPatchFiles());
        dto.setTestInsertions(entity.getTestInsertions());
        dto.setTestDeletions(entity.getTestDeletions());
        dto.setTestTotalChanged(entity.getTestTotalChanged());
        dto.setTestPatchPresent(entity.getTestPatchPresent());
        dto.setFailToPass(entity.getFailToPass());
        dto.setPassToPass(entity.getPassToPass());
        dto.setBenchmarkStatus(entity.getBenchmarkStatus());
        dto.setFailedHistoryStatus(entity.getFailedHistoryStatus());
        dto.setGeneratedOrI18nRatio(entity.getGeneratedOrI18nRatio());
        dto.setScore(entity.getScore());
        dto.setCandidateGrade(entity.getCandidateGrade());
        dto.setGradeReason(entity.getGradeReason());
        dto.setCandidateStatus(entity.getCandidateStatus());
        dto.setDuplicateStatus(entity.getDuplicateStatus());
        dto.setStrengths(parseStringArray(entity.getStrengths()));
        dto.setRisks(parseStringArray(entity.getRisks()));
        dto.setSampleFiles(parseStringArray(entity.getSampleFiles()));
        return dto;
    }

    private List<String> parseStringArray(String value) {
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        try {
            return JSON.parseArray(value, String.class);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private void validateRange(String label, int min, int max) {
        if (max < min) {
            throw new BusinessException(label + "上限不能小于下限");
        }
    }

    private boolean matchesFilters(
            GithubPullCandidateDTO candidate,
            int minGoldSourceFiles,
            int maxGoldSourceFiles,
            int minGoldLines,
            int maxGoldLines) {
        int goldSourceFiles = safeInt(candidate.getGoldSourceFiles());
        int goldLines = safeInt(candidate.getGoldTotalChanged());
        int testPatchFiles = safeInt(candidate.getTestPatchFiles());
        return goldSourceFiles >= minGoldSourceFiles
                && goldSourceFiles <= maxGoldSourceFiles
                && goldLines >= minGoldLines
                && goldLines <= maxGoldLines
                && testPatchFiles >= MIN_TEST_PATCH_FILES;
    }

    private List<JSONObject> getPullFiles(String repo, Integer number) {
        List<JSONObject> files = new ArrayList<>();
        int page = 1;
        while (true) {
            JSONArray chunk = getArray(uri("/repos/" + repo + "/pulls/" + number + "/files")
                    + "?per_page=" + PR_FILES_PER_PAGE + "&page=" + page);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            chunk.stream().map(this::toJsonObject).forEach(files::add);
            if (chunk.size() < PR_FILES_PER_PAGE) {
                break;
            }
            page++;
        }
        return files;
    }

    private GithubPullCandidateDTO toCandidate(
            String repo,
            JSONObject pull,
            List<JSONObject> files,
            List<Integer> issueNumbers,
            IssueContext issueContext,
            int index) {
        PullStats stats = calculateStats(files);
        ScoreResult score = score(stats);
        GradeResult grade = grade(score.score(), stats, score.risks());

        GithubPullCandidateDTO dto = new GithubPullCandidateDTO();
        dto.setCandidateId(String.format("CAND-%04d", index));
        dto.setRepo(repo);
        dto.setNumber(pull.getInteger("number"));
        dto.setTitle(pull.getString("title"));
        dto.setPrUrl(pull.getString("html_url"));
        dto.setIssueUrl(issueContext.primaryIssueUrl());
        dto.setIssueNumbers(JSON.toJSONString(issueNumbers));
        dto.setProblemStatement(issueContext.problemStatement());
        dto.setHintsText(issueContext.hintsText());
        dto.setMergedAt(pull.getString("merged_at"));
        dto.setUpdatedAt(pull.getString("updated_at"));
        dto.setMergeCommit(pull.getString("merge_commit_sha"));
        JSONObject base = pull.getJSONObject("base");
        JSONObject head = pull.getJSONObject("head");
        dto.setBaseCommit(base == null ? null : base.getString("sha"));
        dto.setFixCommit(head == null ? null : head.getString("sha"));
        dto.setPrimaryLanguage(stats.primaryLanguage());
        dto.setSecondaryLanguages(stats.secondaryLanguages());
        dto.setPatchFiles(stats.patchFiles());
        dto.setSourceFiles(stats.sourceFiles());
        dto.setInsertions(stats.insertions());
        dto.setDeletions(stats.deletions());
        dto.setTotalChanged(stats.totalChanged());
        dto.setGoldPatchFiles(stats.goldPatchFiles());
        dto.setGoldSourceFiles(stats.goldSourceFiles());
        dto.setGoldInsertions(stats.goldInsertions());
        dto.setGoldDeletions(stats.goldDeletions());
        dto.setGoldTotalChanged(stats.goldTotalChanged());
        dto.setTestPatchFiles(stats.testPatchFiles());
        dto.setTestInsertions(stats.testInsertions());
        dto.setTestDeletions(stats.testDeletions());
        dto.setTestTotalChanged(stats.testTotalChanged());
        dto.setTestPatchPresent(stats.testPatchFiles() > 0);
        dto.setFailToPass("[]");
        dto.setPassToPass("[]");
        dto.setBenchmarkStatus("UNKNOWN");
        dto.setFailedHistoryStatus("UNKNOWN");
        dto.setGeneratedOrI18nRatio(stats.generatedOrI18nRatio());
        dto.setScore(score.score());
        dto.setStrengths(score.strengths());
        dto.setRisks(score.risks());
        dto.setCandidateGrade(grade.grade());
        dto.setGradeReason(grade.reason());
        dto.setCandidateStatus(score.score() >= 70 ? "scored" : "new");
        dto.setPrecheckPlan(switch (grade.grade()) {
            case "A" -> "优先进入任务制作：拉取 PR refs，拆分 gold/test patch，并补齐验证脚本";
            case "B" -> "进入预检队列：确认测试可写性、环境依赖和模型难度后再制作";
            default -> "暂缓：gold patch 规模、源码文件数或 generated/i18n 风险不满足要求";
        });
        dto.setSampleFiles(files.stream()
                .map(file -> file.getString("filename"))
                .filter(StringUtils::hasText)
                .limit(12)
                .toList());
        return dto;
    }

    static List<Integer> extractResolvedIssueNumbers(String title, String body) {
        String text = ((title == null ? "" : title) + "\n" + (body == null ? "" : body))
                .replaceAll("(?s)<!--.*?-->", "");
        Matcher matcher = RESOLVED_ISSUE_PATTERN.matcher(text);
        Set<Integer> numbers = new LinkedHashSet<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return new ArrayList<>(numbers);
    }

    static IssueRef parseIssueUrl(String issueUrl) {
        if (!StringUtils.hasText(issueUrl)) {
            throw new BusinessException("issueUrl不能为空");
        }
        Matcher matcher = GITHUB_ISSUE_URL_PATTERN.matcher(issueUrl.trim());
        if (!matcher.matches()) {
            throw new BusinessException("issueUrl必须是 GitHub issue 地址，例如 https://github.com/owner/repo/issues/123");
        }
        return new IssueRef(normalizeRepo(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    static boolean closesIssue(String title, String body, int issueNumber) {
        return extractResolvedIssueNumbers(title, body).contains(issueNumber);
    }

    private Integer findClosingPullNumber(IssueRef issueRef) {
        JSONArray events = getArray(uri("/repos/" + issueRef.repo() + "/issues/" + issueRef.number() + "/timeline")
                + "?per_page=100");
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (Object value : events) {
            JSONObject event = toJsonObject(value);
            JSONObject source = event.getJSONObject("source");
            JSONObject issue = source == null ? null : source.getJSONObject("issue");
            String pullUrl = issue == null ? "" : issue.getString("html_url");
            Integer pullNumber = parsePullNumber(pullUrl);
            if (pullNumber != null) {
                return pullNumber;
            }
        }
        return null;
    }

    private Integer parsePullNumber(String pullUrl) {
        if (!StringUtils.hasText(pullUrl)) {
            return null;
        }
        Matcher matcher = GITHUB_PULL_URL_PATTERN.matcher(pullUrl);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private IssueContext fetchIssueContext(String repo, List<Integer> issueNumbers, OffsetDateTime pullCreatedAt) {
        List<String> statements = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        String primaryUrl = "";
        for (Integer issueNumber : issueNumbers) {
            JSONObject issue;
            try {
                issue = getObject(uri("/repos/" + repo + "/issues/" + issueNumber));
            } catch (BusinessException e) {
                continue;
            }
            if (issue == null || issue.isEmpty()) {
                continue;
            }
            if (!StringUtils.hasText(primaryUrl)) {
                primaryUrl = issue.getString("html_url");
            }
            String title = issue.getString("title");
            String body = issue.getString("body");
            StringBuilder statement = new StringBuilder();
            if (StringUtils.hasText(title)) {
                statement.append(title.trim());
            }
            if (StringUtils.hasText(body)) {
                if (!statement.isEmpty()) {
                    statement.append(System.lineSeparator());
                }
                statement.append(body.trim());
            }
            if (!statement.isEmpty()) {
                statements.add(statement.toString());
            }
            hints.addAll(getIssueCommentsBefore(repo, issueNumber, pullCreatedAt));
        }
        return new IssueContext(primaryUrl,
                String.join(System.lineSeparator() + System.lineSeparator(), statements),
                String.join(System.lineSeparator() + System.lineSeparator(), hints));
    }

    private List<String> getIssueCommentsBefore(String repo, Integer issueNumber, OffsetDateTime boundary) {
        if (boundary == null) {
            return List.of();
        }
        JSONArray comments = getArray(uri("/repos/" + repo + "/issues/" + issueNumber + "/comments") + "?per_page=100");
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        for (Object value : comments) {
            JSONObject comment = toJsonObject(value);
            OffsetDateTime updatedAt = parseDate(comment.getString("updated_at"));
            if (updatedAt != null && updatedAt.isBefore(boundary) && StringUtils.hasText(comment.getString("body"))) {
                hints.add(comment.getString("body"));
            }
        }
        return hints;
    }

    private PullStats calculateStats(List<JSONObject> files) {
        int patchFiles = files.size();
        int sourceFiles = 0;
        int insertions = 0;
        int deletions = 0;
        int goldPatchFiles = 0;
        int goldSourceFiles = 0;
        int goldInsertions = 0;
        int goldDeletions = 0;
        int testPatchFiles = 0;
        int testInsertions = 0;
        int testDeletions = 0;
        int generated = 0;
        Map<String, Integer> languages = new LinkedHashMap<>();

        for (JSONObject file : files) {
            String filename = file.getString("filename");
            int additions = safeInt(file.getInteger("additions"));
            int fileDeletions = safeInt(file.getInteger("deletions"));
            boolean source = isSourceFile(filename);
            boolean test = isTestFile(filename);
            insertions += additions;
            deletions += fileDeletions;
            if (source) {
                sourceFiles++;
            }
            if (isGeneratedLike(filename)) {
                generated++;
            }
            classifyLanguage(filename, languages);
            if (test) {
                testPatchFiles++;
                testInsertions += additions;
                testDeletions += fileDeletions;
            } else {
                goldPatchFiles++;
                goldInsertions += additions;
                goldDeletions += fileDeletions;
                if (source) {
                    goldSourceFiles++;
                }
            }
        }

        List<String> orderedLanguages = languages.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        String primaryLanguage = orderedLanguages.isEmpty() ? "unknown" : orderedLanguages.get(0);
        String secondaryLanguages = orderedLanguages.size() <= 1 ? "" : String.join(",", orderedLanguages.subList(1, orderedLanguages.size()));

        return new PullStats(
                patchFiles,
                sourceFiles,
                insertions,
                deletions,
                insertions + deletions,
                goldPatchFiles,
                goldSourceFiles,
                goldInsertions,
                goldDeletions,
                goldInsertions + goldDeletions,
                testPatchFiles,
                testInsertions,
                testDeletions,
                testInsertions + testDeletions,
                Math.round((generated * 100.0 / Math.max(patchFiles, 1))) / 100.0,
                primaryLanguage,
                secondaryLanguages
        );
    }

    private ScoreResult score(PullStats stats) {
        int score = 0;
        List<String> strengths = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        if (stats.goldTotalChanged() >= STRONG_GOLD_LINES) {
            score += 35;
            strengths.add("gold_patch>=300");
        } else if (stats.goldTotalChanged() >= PREFERRED_GOLD_LINES) {
            score += 25;
            strengths.add("gold_patch>=200");
        } else if (stats.goldTotalChanged() >= MIN_GOLD_LINES) {
            score += 15;
            strengths.add("gold_patch>=108");
        } else {
            risks.add("gold_patch_too_small");
        }

        if (stats.goldSourceFiles() >= 6) {
            score += 25;
            strengths.add("gold_source_files>=6");
        } else if (stats.goldSourceFiles() >= 4) {
            score += 18;
            strengths.add("gold_source_files>=4");
        } else {
            risks.add("too_few_gold_source_files");
        }

        if (stats.goldPatchFiles() >= 8) {
            score += 15;
            strengths.add("broad_gold_scope");
        } else if (stats.goldPatchFiles() >= MIN_GOLD_FILES) {
            score += 8;
            strengths.add("gold_files>=5");
        } else {
            risks.add("too_few_gold_files");
        }

        if (stats.totalChanged() >= 300
                && stats.goldTotalChanged() < PREFERRED_GOLD_LINES
                && stats.testTotalChanged() > stats.goldTotalChanged()) {
            risks.add("test_heavy_pr_gold_below_preferred");
        }
        if (stats.generatedOrI18nRatio() > 0.5) {
            risks.add("mostly_generated_or_i18n");
        } else {
            score += 15;
        }
        if (stats.goldTotalChanged() > 1500) {
            risks.add("very_large_patch_may_be_hard_to_test");
        }
        score += 10;
        return new ScoreResult(Math.min(score, 100), strengths, risks);
    }

    private GradeResult grade(int score, PullStats stats, List<String> risks) {
        List<String> hardRisks = List.of(
                "gold_patch_too_small",
                "too_few_gold_files",
                "too_few_gold_source_files",
                "mostly_generated_or_i18n"
        );
        if (risks.stream().anyMatch(hardRisks::contains)
                || stats.goldTotalChanged() < MIN_GOLD_LINES
                || stats.goldPatchFiles() < MIN_GOLD_FILES) {
            return new GradeResult("C", "淘汰：gold/ground-truth 生产补丁规模、源码文件数或 generated/i18n 占比不满足硬线");
        }
        if (risks.contains("test_heavy_pr_gold_below_preferred")) {
            return new GradeResult("B", "备用：gold/ground-truth 满足最低线，但低于推荐规模且测试改动占比较高");
        }
        if (score >= 80) {
            return new GradeResult("A", "优先：gold/ground-truth 生产补丁规模达标、真实工程变更、测试可写性较好");
        }
        if (score >= 70) {
            return new GradeResult("B", "备用：gold/ground-truth 生产补丁基本合格但测试、环境或模型难度仍需预检");
        }
        return new GradeResult("C", "淘汰/暂缓：综合评分不足");
    }

    private JSONArray getArray(String url) {
        Object payload = webClient.get()
                .uri(url)
                .headers(this::applyOptionalToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BusinessException("GitHub PR 扫描失败: " + extractGithubErrorMessage(body))))
                .bodyToMono(Object.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        if (payload == null) {
            return new JSONArray();
        }
        return JSON.parseArray(JSON.toJSONString(payload));
    }

    private JSONObject getObject(String url) {
        Object payload = webClient.get()
                .uri(url)
                .headers(this::applyOptionalToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BusinessException("GitHub issue fetch failed: " + extractGithubErrorMessage(body))))
                .bodyToMono(Object.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        if (payload == null) {
            return new JSONObject();
        }
        return toJsonObject(payload);
    }

    private String uri(String path) {
        return path;
    }

    private JSONObject toJsonObject(Object value) {
        if (value instanceof JSONObject object) {
            return object;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    private static String normalizeRepo(String repo) {
        if (!StringUtils.hasText(repo)) {
            throw new BusinessException("repo不能为空");
        }
        String normalized = repo.trim();
        if (!normalized.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new BusinessException("repo格式必须为 owner/name");
        }
        return normalized;
    }

    private void applyOptionalToken(HttpHeaders headers) {
        String token = GithubTokenContext.currentToken();
        if (!StringUtils.hasText(token)) {
            token = githubToken;
        }
        if (runtimeSettingsService != null) {
            token = runtimeSettingsService.resolveGithubToken(token);
        }
        if (!StringUtils.hasText(token)) {
            token = System.getenv("GITHUB_TOKEN");
        }
        if (!StringUtils.hasText(token)) {
            token = System.getenv("GH_TOKEN");
        }
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token);
        }
    }

    private String extractGithubErrorMessage(String body) {
        if (!StringUtils.hasText(body)) {
            return "上游接口返回错误";
        }
        try {
            JSONObject payload = JSON.parseObject(body);
            String message = payload.getString("message");
            return StringUtils.hasText(message) ? message : body;
        } catch (Exception e) {
            return body;
        }
    }

    private OffsetDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(String value) {
        OffsetDateTime parsed = parseDate(value);
        return parsed == null ? null : parsed.toLocalDateTime();
    }

    private boolean isTestFile(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("/");
        String name = parts.length == 0 ? lower : parts[parts.length - 1];
        return List.of(parts).contains("test")
                || List.of(parts).contains("tests")
                || List.of(parts).contains("__tests__")
                || name.startsWith("test_")
                || name.endsWith("_test.go")
                || name.endsWith(".test.js")
                || name.endsWith(".test.jsx")
                || name.endsWith(".test.ts")
                || name.endsWith(".test.tsx")
                || name.endsWith(".spec.js")
                || name.endsWith(".spec.jsx")
                || name.endsWith(".spec.ts")
                || name.endsWith(".spec.tsx");
    }

    private boolean isSourceFile(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return NON_SOURCE_SUFFIXES.stream().noneMatch(lower::endsWith);
    }

    private boolean isGeneratedLike(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return GENERATED_NEEDLES.stream().anyMatch(lower::contains);
    }

    private void classifyLanguage(String filename, Map<String, Integer> languages) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : LANGUAGE_EXTENSIONS.entrySet()) {
            if (entry.getValue().stream().anyMatch(lower::endsWith)) {
                languages.put(entry.getKey(), languages.getOrDefault(entry.getKey(), 0) + 1);
                return;
            }
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private void applyProxyIfAvailable(WebClient.Builder builder) {
        if (!isGithubProxyAvailable()) {
            return;
        }
        HttpClient httpClient = HttpClient.create()
                .proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                        .host(GITHUB_PROXY_HOST)
                        .port(GITHUB_PROXY_PORT));
        builder.clientConnector(new ReactorClientHttpConnector(httpClient));
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

    private record PullStats(
            int patchFiles,
            int sourceFiles,
            int insertions,
            int deletions,
            int totalChanged,
            int goldPatchFiles,
            int goldSourceFiles,
            int goldInsertions,
            int goldDeletions,
            int goldTotalChanged,
            int testPatchFiles,
            int testInsertions,
            int testDeletions,
            int testTotalChanged,
            double generatedOrI18nRatio,
            String primaryLanguage,
            String secondaryLanguages
    ) {
    }

    private record ScoreResult(int score, List<String> strengths, List<String> risks) {
    }

    private record GradeResult(String grade, String reason) {
    }

    private record IssueContext(String primaryIssueUrl, String problemStatement, String hintsText) {
    }

    record IssueRef(String repo, int number) {
    }
}
