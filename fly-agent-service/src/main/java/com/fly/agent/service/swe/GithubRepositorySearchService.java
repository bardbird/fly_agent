package com.fly.agent.service.swe;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.swe.GithubRepositoryDTO;
import com.fly.agent.common.dto.swe.GithubRepositorySearchRequest;
import com.fly.agent.common.dto.swe.GithubRepositorySearchResponse;
import com.fly.agent.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Searches GitHub repositories by primary language for SWE-Pro target discovery.
 */
@Slf4j
@Service
public class GithubRepositorySearchService {

    private static final int DEFAULT_MIN_STARS = 100;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PER_PAGE = 20;
    private static final int MAX_PER_PAGE = 50;
    private static final String DEFAULT_SORT = "stars";
    private static final String DEFAULT_ORDER = "desc";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final int GITHUB_PROXY_CONNECT_TIMEOUT_MS = 500;
    private static final String GITHUB_PROXY_HOST = "127.0.0.1";
    private static final int GITHUB_PROXY_PORT = 7897;

    private static final Map<String, String> LANGUAGE_MAP = new LinkedHashMap<>();

    static {
        LANGUAGE_MAP.put("c", "C");
        LANGUAGE_MAP.put("c++", "C++");
        LANGUAGE_MAP.put("ruby", "Ruby");
        LANGUAGE_MAP.put("rust", "Rust");
        LANGUAGE_MAP.put("go", "Go");
        LANGUAGE_MAP.put("js", "JavaScript");
        LANGUAGE_MAP.put("javascript", "JavaScript");
        LANGUAGE_MAP.put("php", "PHP");
        LANGUAGE_MAP.put("ts", "TypeScript");
        LANGUAGE_MAP.put("typescript", "TypeScript");
        LANGUAGE_MAP.put("python", "Python");
        LANGUAGE_MAP.put("java", "Java");
    }

    private final WebClient webClient;
    private final String githubToken;
    private final SweRepoPrecheckService repoPrecheckService;

    public GithubRepositorySearchService(
            @Value("${swe.github.token:}") String githubToken,
            SweRepoPrecheckService repoPrecheckService) {
        this.githubToken = githubToken;
        this.repoPrecheckService = repoPrecheckService;
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "fly-agent-swe-pro")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024));
        applyProxyIfAvailable(builder);
        this.webClient = builder.build();
    }

    public GithubRepositorySearchResponse search(GithubRepositorySearchRequest request) {
        String normalizedLanguage = normalizeLanguage(request.getLanguage());
        String githubLanguage = LANGUAGE_MAP.get(normalizedLanguage);
        if (githubLanguage == null) {
            throw new BusinessException("不支持的语言: " + request.getLanguage());
        }

        int minStars = request.getMinStars() == null ? DEFAULT_MIN_STARS : Math.max(request.getMinStars(), 0);
        Integer maxStars = request.getMaxStars() == null ? null : Math.max(request.getMaxStars(), 0);
        if (maxStars != null && maxStars < minStars) {
            throw new BusinessException("Stars 上限不能小于下限");
        }
        int page = request.getPage() == null ? DEFAULT_PAGE : Math.max(request.getPage(), 1);
        int perPage = request.getPerPage() == null ? DEFAULT_PER_PAGE : Math.min(Math.max(request.getPerPage(), 1), MAX_PER_PAGE);
        String sort = normalizeSort(request.getSort());
        String order = normalizeOrder(request.getOrder());
        String query = buildQuery(githubLanguage, request.getKeyword(), minStars, maxStars);

        JSONObject payload = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/repositories")
                        .queryParam("q", query)
                        .queryParam("sort", sort)
                        .queryParam("order", order)
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build())
                .headers(this::applyOptionalToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new BusinessException("GitHub 搜索失败: " + extractGithubErrorMessage(body))))
                .bodyToMono(JSONObject.class)
                .timeout(REQUEST_TIMEOUT)
                .block();

        if (payload == null) {
            throw new BusinessException("GitHub 搜索无响应");
        }

        GithubRepositorySearchResponse response = new GithubRepositorySearchResponse();
        response.setLanguage(normalizedLanguage);
        response.setGithubLanguage(githubLanguage);
        response.setTotalCount(payload.getInteger("total_count"));
        response.setIncompleteResults(payload.getBoolean("incomplete_results"));
        response.setPage(page);
        response.setPerPage(perPage);

        JSONArray items = payload.getJSONArray("items");
        if (items != null) {
            response.setRepositories(items.stream()
                    .map(this::toJsonObject)
                    .map(this::toRepository)
                    .filter(repository -> !Boolean.FALSE.equals(request.getPrecheckFilter())
                            || repoPrecheckService.allows(repository.getFullName()))
                    .toList());
        }
        return response;
    }

    private JSONObject toJsonObject(Object value) {
        if (value instanceof JSONObject object) {
            return object;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    private String normalizeLanguage(String language) {
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return DEFAULT_SORT;
        }
        return "stars".equalsIgnoreCase(sort.trim()) ? "stars" : DEFAULT_SORT;
    }

    private String normalizeOrder(String order) {
        if (!StringUtils.hasText(order)) {
            return DEFAULT_ORDER;
        }
        return "asc".equalsIgnoreCase(order.trim()) ? "asc" : DEFAULT_ORDER;
    }

    private String buildQuery(String githubLanguage, String keyword, int minStars, Integer maxStars) {
        StringBuilder query = new StringBuilder();
        if (StringUtils.hasText(keyword)) {
            query.append(keyword.trim()).append(' ');
        }
        query.append("language:").append(githubLanguage).append(' ');
        if (maxStars == null) {
            query.append("stars:>=").append(minStars).append(' ');
        } else {
            query.append("stars:").append(minStars).append("..").append(maxStars).append(' ');
        }
        query.append("archived:false");
        return query.toString();
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

    private GithubRepositoryDTO toRepository(JSONObject item) {
        GithubRepositoryDTO dto = new GithubRepositoryDTO();
        dto.setGithubId(item.getLong("id"));
        dto.setName(item.getString("name"));
        dto.setFullName(item.getString("full_name"));
        dto.setHtmlUrl(item.getString("html_url"));
        dto.setDescription(item.getString("description"));
        dto.setLanguage(item.getString("language"));
        dto.setStargazersCount(item.getInteger("stargazers_count"));
        dto.setForksCount(item.getInteger("forks_count"));
        dto.setOpenIssuesCount(item.getInteger("open_issues_count"));
        dto.setDefaultBranch(item.getString("default_branch"));
        dto.setPushedAt(item.getString("pushed_at"));
        dto.setArchived(item.getBoolean("archived"));
        dto.setDisabled(item.getBoolean("disabled"));
        JSONObject license = item.getJSONObject("license");
        if (license != null) {
            dto.setLicenseSpdxId(license.getString("spdx_id"));
            dto.setLicenseName(license.getString("name"));
        }

        JSONArray topics = item.getJSONArray("topics");
        if (topics != null) {
            dto.setTopics(topics.stream()
                    .map(String::valueOf)
                    .toList());
        }
        applyRepositoryScore(dto);
        return dto;
    }

    private void applyRepositoryScore(GithubRepositoryDTO dto) {
        int score = 0;
        List<String> strengths = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        int stars = safeInt(dto.getStargazersCount());
        if (stars >= 10000) {
            score += 25;
            strengths.add("stars>=10000");
        } else if (stars >= 3000) {
            score += 20;
            strengths.add("stars>=3000");
        } else if (stars >= 1000) {
            score += 15;
            strengths.add("stars>=1000");
        } else if (stars >= 100) {
            score += 8;
            strengths.add("stars>=100");
        } else {
            risks.add("low_star_signal");
        }

        int forks = safeInt(dto.getForksCount());
        if (forks >= 1000) {
            score += 15;
            strengths.add("forks>=1000");
        } else if (forks >= 300) {
            score += 10;
            strengths.add("forks>=300");
        } else if (forks >= 50) {
            score += 5;
            strengths.add("forks>=50");
        }

        int openIssues = safeInt(dto.getOpenIssuesCount());
        if (openIssues >= 50) {
            score += 20;
            strengths.add("open_issues>=50");
        } else if (openIssues >= 10) {
            score += 12;
            strengths.add("open_issues>=10");
        } else if (openIssues > 0) {
            score += 6;
            strengths.add("open_issues>0");
        } else {
            risks.add("weak_issue_signal");
        }

        long daysSincePush = daysSincePush(dto.getPushedAt());
        if (daysSincePush <= 30) {
            score += 20;
            strengths.add("pushed_within_30d");
        } else if (daysSincePush <= 90) {
            score += 15;
            strengths.add("pushed_within_90d");
        } else if (daysSincePush <= 365) {
            score += 8;
            strengths.add("pushed_within_365d");
        } else {
            risks.add("stale_repository");
        }

        if (Boolean.TRUE.equals(dto.getArchived()) || Boolean.TRUE.equals(dto.getDisabled())) {
            risks.add("archived_or_disabled");
            score = Math.min(score, 40);
        }

        String description = dto.getDescription() == null ? "" : dto.getDescription().toLowerCase(Locale.ROOT);
        List<String> topics = dto.getTopics() == null ? List.of() : dto.getTopics();
        boolean benchmarkRisk = description.contains("awesome")
                || description.contains("list")
                || topics.stream().anyMatch(topic -> topic.toLowerCase(Locale.ROOT).contains("awesome"));
        if (benchmarkRisk) {
            risks.add("curated_list_or_non_product_repo");
            score = Math.min(score, 65);
        } else {
            score += 10;
            strengths.add("product_or_library_repo");
        }

        String grade;
        String reason;
        if (risks.contains("archived_or_disabled") || score < 70) {
            grade = "C";
            reason = "淘汰/暂缓：仓库活跃度、真实工程信号或可挖掘 issue 信号不足";
        } else if (score >= 85 && !risks.contains("curated_list_or_non_product_repo")) {
            grade = "A";
            reason = "优先：仓库活跃、社区规模足够，适合继续扫描 merged PR 并按 diff 评分";
        } else {
            grade = "B";
            reason = "备用：仓库具备候选价值，但需要进一步扫描 merged PR 验证 patch 规模和测试可写性";
        }

        dto.setProductionScore(Math.min(score, 100));
        dto.setCandidateGrade(grade);
        dto.setGradeReason(reason);
        dto.setStrengths(strengths);
        dto.setRisks(risks);
        dto.setPrecheckPlan(switch (grade) {
            case "A" -> "进入 merged PR 扫描，优先寻找 gold_patch>=200、gold_source_files>=4 的候选";
            case "B" -> "低成本扫描最近 merged PR，若 30 分钟内无合格 patch 则暂缓";
            default -> "不进入制作，除非用户明确指定该 repo";
        });
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long daysSincePush(String pushedAt) {
        if (!StringUtils.hasText(pushedAt)) {
            return Long.MAX_VALUE;
        }
        try {
            return ChronoUnit.DAYS.between(OffsetDateTime.parse(pushedAt), OffsetDateTime.now());
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
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
}
