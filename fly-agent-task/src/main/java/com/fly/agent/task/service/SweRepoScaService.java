package com.fly.agent.task.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.swe.GithubRepositoryDTO;
import com.fly.agent.dao.entity.swe.SweRepoScaReportEntity;
import com.fly.agent.dao.mapper.swe.SweRepoScaReportMapper;
import com.fly.agent.service.swe.GithubTokenContext;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates repo-level SCA license reports and enforces the commercial AI
 * training compatibility gate before candidate scanning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SweRepoScaService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String GITHUB_API_BASE_URL = "https://api.github.com";

    private static final Set<String> COMMERCIAL_AI_COMPATIBLE_LICENSES = Set.of(
            "0BSD",
            "Apache-2.0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "CC0-1.0",
            "ISC",
            "MIT",
            "Unlicense",
            "Zlib");

    private static final Set<String> INCOMPATIBLE_LICENSES = Set.of(
            "AGPL-3.0",
            "GPL-2.0",
            "GPL-3.0",
            "LGPL-2.1",
            "LGPL-3.0",
            "MPL-2.0",
            "EPL-1.0",
            "EPL-2.0",
            "CDDL-1.0",
            "CDDL-1.1");

    private final SweRepoScaReportMapper scaReportMapper;

    @Value("${swe.github.token:}")
    private String githubToken;

    private final WebClient webClient = WebClient.builder()
            .baseUrl(GITHUB_API_BASE_URL)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader(HttpHeaders.USER_AGENT, "fly-agent-swe-pro")
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    @PostConstruct
    public void initializeSchema() {
        // Schema is managed by Flyway migration V6__swe_repo_precheck_tables.sql.
    }

    public ScaDecision analyzeRepo(String repo) {
        return analyzeRepo(repo, null, null, null, null, null);
    }

    public ScaDecision analyzeRepo(
            GithubRepositoryDTO repository,
            String searchKeyword,
            Integer searchMinStars,
            Integer searchMaxStars) {
        if (repository == null) {
            return reject(null, null, null, "repo格式无效，无法生成SCA报告", null);
        }
        return analyzeRepo(
                repository.getFullName(),
                normalizeLanguage(repository.getLanguage()),
                repository.getStargazersCount(),
                searchKeyword,
                searchMinStars,
                searchMaxStars);
    }

    private ScaDecision analyzeRepo(
            String repo,
            String primaryLanguage,
            Integer githubStars,
            String searchKeyword,
            Integer searchMinStars,
            Integer searchMaxStars) {
        initializeSchema();
        String normalizedRepo = SweRepoBlacklistService.normalizeRepo(repo);
        if (!StringUtils.hasText(normalizedRepo)) {
            return reject(repo, null, null, "repo格式无效，无法生成SCA报告", null);
        }

        JSONObject payload = fetchLicense(normalizedRepo);
        JSONObject license = payload == null ? null : payload.getJSONObject("license");
        String spdxId = license == null ? null : license.getString("spdx_id");
        String licenseName = license == null ? null : license.getString("name");
        ScaDecision decision = decide(normalizedRepo, spdxId, licenseName, payload);
        decision.setPrimaryLanguage(primaryLanguage);
        decision.setGithubStars(githubStars);
        decision.setSearchKeyword(normalizeKeyword(searchKeyword));
        decision.setSearchMinStars(searchMinStars);
        decision.setSearchMaxStars(searchMaxStars);
        persist(decision);
        return decision;
    }

    public List<String> listAllowedReposForCandidateScan(int limit, int offset) {
        return scaReportMapper.selectAllowedReposForCandidateScan(Math.max(limit, 1), Math.max(offset, 0));
    }

    public int countReposInScanScope(String language, String keyword, Integer minStars, Integer maxStars) {
        return scaReportMapper.countReposInScanScope(
                normalizeLanguage(language),
                normalizeKeyword(keyword),
                minStars,
                maxStars);
    }

    public int countReposCheckedOnDateInScanScope(
            String language,
            String keyword,
            Integer minStars,
            Integer maxStars,
            LocalDate scanDate) {
        return scaReportMapper.countReposCheckedOnDateInScanScope(
                normalizeLanguage(language),
                normalizeKeyword(keyword),
                minStars,
                maxStars,
                scanDate);
    }

    public int countCandidateScannedOnDateInScanScope(
            String language,
            String keyword,
            Integer minStars,
            Integer maxStars,
            LocalDate scanDate) {
        return scaReportMapper.countCandidateScannedOnDateInScanScope(
                normalizeLanguage(language),
                normalizeKeyword(keyword),
                minStars,
                maxStars,
                scanDate);
    }

    public List<String> listAllowedReposForCandidateScan(
            String language,
            String keyword,
            Integer minStars,
            Integer maxStars,
            LocalDate scanDate,
            int limit,
            int offset) {
        return scaReportMapper.selectAllowedReposInScanScope(
                normalizeLanguage(language),
                normalizeKeyword(keyword),
                minStars,
                maxStars,
                scanDate,
                Math.max(limit, 1),
                Math.max(offset, 0));
    }

    public void markCandidateScanAttempt(String repo) {
        String normalizedRepo = SweRepoBlacklistService.normalizeRepo(repo);
        if (StringUtils.hasText(normalizedRepo)) {
            scaReportMapper.markCandidateScanAttempt(normalizedRepo);
        }
    }

    public static LicensePrecheckDecision precheckLicense(String spdxId, String licenseName) {
        if (!StringUtils.hasText(spdxId) || "NOASSERTION".equalsIgnoreCase(spdxId)) {
            return LicensePrecheckDecision.reject(spdxId, licenseName, "未识别到明确 SPDX 许可证，按硬性SCA规则拒绝");
        }
        String normalizedSpdxId = spdxId.trim();
        if (COMMERCIAL_AI_COMPATIBLE_LICENSES.contains(normalizedSpdxId)) {
            return LicensePrecheckDecision.allow(normalizedSpdxId, licenseName, "许可证在商业AI训练兼容白名单内");
        }
        if (INCOMPATIBLE_LICENSES.contains(normalizedSpdxId) || isCopyleftLike(normalizedSpdxId)) {
            return LicensePrecheckDecision.reject(normalizedSpdxId, licenseName, "许可证具有copyleft/reciprocal义务或商业AI训练兼容性风险");
        }
        return LicensePrecheckDecision.reject(normalizedSpdxId, licenseName, "许可证不在商业AI训练兼容白名单内，按硬性SCA规则拒绝");
    }

    private JSONObject fetchLicense(String repo) {
        return webClient.get()
                .uri("/repos/" + repo + "/license")
                .headers(this::applyOptionalToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException("GitHub license SCA failed: " + body)))
                .bodyToMono(JSONObject.class)
                .timeout(REQUEST_TIMEOUT)
                .onErrorReturn(new JSONObject())
                .block();
    }

    private ScaDecision decide(String repo, String spdxId, String licenseName, JSONObject rawPayload) {
        LicensePrecheckDecision precheck = precheckLicense(spdxId, licenseName);
        if (precheck.allowed()) {
            return allow(repo, precheck.spdxId(), licenseName, precheck.reason(), rawPayload);
        }
        return reject(repo, precheck.spdxId(), licenseName, precheck.reason(), rawPayload);
    }

    private static boolean isCopyleftLike(String spdxId) {
        String value = spdxId.toUpperCase(Locale.ROOT);
        return value.contains("GPL") || value.contains("AGPL") || value.contains("LGPL")
                || value.contains("MPL") || value.contains("EPL") || value.contains("CDDL");
    }

    private ScaDecision allow(String repo, String spdxId, String licenseName, String reason, JSONObject rawPayload) {
        return decision(repo, spdxId, licenseName, "ALLOW", reason, rawPayload);
    }

    private ScaDecision reject(String repo, String spdxId, String licenseName, String reason, JSONObject rawPayload) {
        return decision(repo, spdxId, licenseName, "REJECT", reason, rawPayload);
    }

    private ScaDecision decision(String repo, String spdxId, String licenseName, String status, String reason, JSONObject rawPayload) {
        ScaDecision decision = new ScaDecision();
        decision.setRepo(repo);
        decision.setToolName("GitHub License API SCA");
        decision.setLicenseSpdxId(spdxId);
        decision.setLicenseName(licenseName);
        decision.setCompatibilityStatus(status);
        decision.setCompatibilityReason(reason);
        decision.setComponentCount(1);
        decision.setReportJson(JSON.toJSONString(report(repo, spdxId, licenseName, status, reason)));
        decision.setRawJson(rawPayload == null ? null : rawPayload.toJSONString());
        decision.setCheckedAt(LocalDateTime.now());
        return decision;
    }

    private JSONObject report(String repo, String spdxId, String licenseName, String status, String reason) {
        JSONObject report = new JSONObject();
        report.put("reportType", "software_composition_analysis");
        report.put("scope", "repository_source_code");
        report.put("policy", "commercial_ai_training_license_gate");
        report.put("compatibilityStatus", status);
        report.put("compatibilityReason", reason);
        report.put("compatibleLicenseAllowlist", COMMERCIAL_AI_COMPATIBLE_LICENSES);

        JSONObject component = new JSONObject();
        component.put("componentName", repo);
        component.put("componentType", "github_repository_source");
        component.put("licenseSpdxId", spdxId);
        component.put("licenseName", licenseName);
        component.put("includedInTrainingData", "ALLOW".equals(status));
        component.put("commercialAiTrainingCompatible", "ALLOW".equals(status));

        JSONArray components = new JSONArray();
        components.add(component);
        report.put("components", components);
        return report;
    }

    private void persist(ScaDecision decision) {
        SweRepoScaReportEntity entity = new SweRepoScaReportEntity();
        entity.setRepo(decision.getRepo());
        entity.setPrimaryLanguage(decision.getPrimaryLanguage());
        entity.setGithubStars(decision.getGithubStars());
        entity.setSearchKeyword(decision.getSearchKeyword());
        entity.setSearchMinStars(decision.getSearchMinStars());
        entity.setSearchMaxStars(decision.getSearchMaxStars());
        entity.setToolName(decision.getToolName());
        entity.setLicenseSpdxId(decision.getLicenseSpdxId());
        entity.setLicenseName(decision.getLicenseName());
        entity.setCompatibilityStatus(decision.getCompatibilityStatus());
        entity.setCompatibilityReason(decision.getCompatibilityReason());
        entity.setComponentCount(decision.getComponentCount());
        entity.setReportJson(decision.getReportJson());
        entity.setRawJson(decision.getRawJson());
        entity.setCheckedAt(decision.getCheckedAt());
        scaReportMapper.upsert(entity);
    }

    private void applyOptionalToken(HttpHeaders headers) {
        String token = GithubTokenContext.currentToken();
        if (!StringUtils.hasText(token)) {
            token = githubToken;
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

    public static String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "js" -> "javascript";
            case "ts" -> "typescript";
            default -> normalized;
        };
    }

    private String normalizeKeyword(String keyword) {
        return StringUtils.hasText(keyword) ? keyword.trim() : null;
    }

    @Data
    public static class ScaDecision {

        private String repo;

        private String primaryLanguage;

        private Integer githubStars;

        private String searchKeyword;

        private Integer searchMinStars;

        private Integer searchMaxStars;

        private String toolName;

        private String licenseSpdxId;

        private String licenseName;

        private String compatibilityStatus;

        private String compatibilityReason;

        private Integer componentCount;

        private String reportJson;

        private String rawJson;

        private LocalDateTime checkedAt;

        public boolean isAllowed() {
            return "ALLOW".equals(compatibilityStatus);
        }
    }

    public record LicensePrecheckDecision(boolean allowed, String spdxId, String licenseName, String reason) {

        public static LicensePrecheckDecision allow(String spdxId, String licenseName, String reason) {
            return new LicensePrecheckDecision(true, spdxId, licenseName, reason);
        }

        public static LicensePrecheckDecision reject(String spdxId, String licenseName, String reason) {
            return new LicensePrecheckDecision(false, spdxId, licenseName, reason);
        }
    }
}
