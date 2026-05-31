package com.fly.agent.task.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.swe.GithubRepositoryDTO;
import com.fly.agent.service.swe.GithubTokenContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight repository profile used by SWE repo discovery before the
 * license-only SCA gate. It uses GitHub API metadata only, so discovery does
 * not need to clone repositories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubRepoProfileService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String GITHUB_API_BASE_URL = "https://api.github.com";
    private static final int GITHUB_PROXY_CONNECT_TIMEOUT_MS = 500;
    private static final String GITHUB_PROXY_HOST = "127.0.0.1";
    private static final int GITHUB_PROXY_PORT = 7897;
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final double LANGUAGE_NOISE_FLOOR = 0.01d;

    private static final Set<String> IGNORED_PATH_PARTS = Set.of(
            ".git",
            ".github",
            "node_modules",
            "vendor",
            "third_party",
            "third-party",
            "external",
            "examples",
            "example",
            "docs",
            "doc",
            "testdata",
            "fixtures",
            "dist",
            "build",
            "target");

    private static final Set<String> MANIFEST_NAMES = Set.of(
            "package.json",
            "pyproject.toml",
            "setup.cfg",
            "requirements.txt",
            "requirements-dev.txt",
            "go.mod",
            "Cargo.toml",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "composer.json",
            "Gemfile",
            "vcpkg.json",
            "conanfile.txt",
            "conanfile.py",
            "CMakeLists.txt");

    @Value("${swe.github.token:}")
    private String githubToken;

    private final org.springframework.web.reactive.function.client.WebClient webClient = buildWebClient();

    public RepoProfileDecision analyze(GithubRepositoryDTO repository, ProfileConstraints constraints) {
        RepoProfileDecision decision = new RepoProfileDecision();
        decision.setRepo(repository == null ? null : repository.getFullName());
        decision.setAllowed(true);
        decision.setReasonCode("profile_filter_passed");
        decision.setReason("Repository profile passed language and dependency filters");

        if (repository == null || !StringUtils.hasText(repository.getFullName())) {
            return reject(decision, "invalid_repo", "Repository metadata is missing");
        }
        if (!Boolean.TRUE.equals(constraints.getEnabled())) {
            decision.setReasonCode("profile_filter_disabled");
            decision.setReason("Repository profile filter is disabled");
            return decision;
        }

        try {
            LanguageProfile languageProfile = fetchLanguageProfile(repository.getFullName());
            decision.setPrimaryLanguageRatio(languageProfile.getPrimaryLanguageRatio());
            decision.setLanguageCount(languageProfile.getEffectiveLanguageCount());
            decision.setLanguages(languageProfile.getLanguages());

            if (languageProfile.getPrimaryLanguageRatio() < constraints.getMinPrimaryLanguageRatio()) {
                return reject(decision, "primary_language_ratio_too_low",
                        "Primary language ratio is below threshold");
            }
            if (languageProfile.getEffectiveLanguageCount() > constraints.getMaxLanguageCount()) {
                return reject(decision, "too_many_languages",
                        "Repository has too many effective languages");
            }

            DependencyProfile dependencyProfile = fetchDependencyProfile(repository, constraints);
            decision.setManifestCount(dependencyProfile.getManifestCount());
            decision.setDownloadedManifestCount(dependencyProfile.getDownloadedManifestCount());
            decision.setDirectDependencyCount(dependencyProfile.getDirectDependencyCount());
            decision.setManifests(dependencyProfile.getManifests());

            if (dependencyProfile.getManifestCount() > constraints.getMaxManifestCount()) {
                return reject(decision, "too_many_manifests",
                        "Repository has too many dependency manifests");
            }
            if (dependencyProfile.getDirectDependencyCount() > constraints.getMaxDirectDependencies()) {
                return reject(decision, "too_many_direct_dependencies",
                        "Repository has too many direct dependencies");
            }

            decision.setScore(score(decision, constraints));
            return decision;
        } catch (Exception e) {
            decision.setReasonCode("profile_check_failed_open");
            decision.setReason("Repository profile check failed; allowing repo to avoid discovery interruption");
            decision.setProfileError(e.getMessage());
            decision.setScore(score(decision, constraints));
            decision.setAllowed(true);
            log.warn("SWE repo profile check failed open, repo={}", repository.getFullName(), e);
            return decision;
        }
    }

    private RepoProfileDecision reject(RepoProfileDecision decision, String reasonCode, String reason) {
        decision.setAllowed(false);
        decision.setReasonCode(reasonCode);
        decision.setReason(reason);
        decision.setScore(score(decision, null));
        return decision;
    }

    private int score(RepoProfileDecision decision, ProfileConstraints constraints) {
        int score = 0;
        double minPrimaryLanguageRatio = constraints == null ? 0.70d : constraints.getMinPrimaryLanguageRatio();
        int maxDirectDependencies = constraints == null ? 30 : constraints.getMaxDirectDependencies();

        Double ratio = decision.getPrimaryLanguageRatio();
        if (ratio != null) {
            if (ratio >= 0.90d) {
                score += 30;
            } else if (ratio >= 0.80d) {
                score += 25;
            } else if (ratio >= minPrimaryLanguageRatio) {
                score += 18;
            }
        }
        Integer languageCount = decision.getLanguageCount();
        if (languageCount != null) {
            if (languageCount <= 2) {
                score += 20;
            } else if (languageCount <= 4) {
                score += 12;
            }
        }
        Integer directDependencies = decision.getDirectDependencyCount();
        if (directDependencies != null) {
            if (directDependencies <= 5) {
                score += 30;
            } else if (directDependencies <= 10) {
                score += 25;
            } else if (directDependencies <= 20) {
                score += 15;
            } else if (directDependencies <= maxDirectDependencies) {
                score += 8;
            }
        }
        Integer manifestCount = decision.getManifestCount();
        if (manifestCount != null) {
            if (manifestCount <= 2) {
                score += 20;
            } else if (manifestCount <= 5) {
                score += 12;
            }
        }
        return Math.min(score, 100);
    }

    private LanguageProfile fetchLanguageProfile(String repo) {
        JSONObject payload = webClient.get()
                .uri("/repos/" + repo + "/languages")
                .headers(this::applyOptionalToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException("GitHub languages API failed: " + body)))
                .bodyToMono(JSONObject.class)
                .timeout(REQUEST_TIMEOUT)
                .block();
        if (payload == null || payload.isEmpty()) {
            throw new IllegalStateException("GitHub languages API returned empty payload");
        }

        long totalBytes = payload.values().stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .sum();
        if (totalBytes <= 0) {
            throw new IllegalStateException("Repository language byte total is zero");
        }

        List<LanguageShare> languages = payload.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Number)
                .map(entry -> new LanguageShare(
                        entry.getKey(),
                        ((Number) entry.getValue()).longValue(),
                        ((Number) entry.getValue()).doubleValue() / totalBytes))
                .sorted(Comparator.comparing(LanguageShare::bytes).reversed())
                .toList();

        LanguageProfile profile = new LanguageProfile();
        profile.setPrimaryLanguageRatio(languages.isEmpty() ? 0.0d : languages.get(0).ratio());
        profile.setEffectiveLanguageCount((int) languages.stream()
                .filter(language -> language.ratio() >= LANGUAGE_NOISE_FLOOR)
                .count());
        profile.setLanguages(languages);
        return profile;
    }

    private DependencyProfile fetchDependencyProfile(GithubRepositoryDTO repository, ProfileConstraints constraints) {
        String repo = repository.getFullName();
        String branch = StringUtils.hasText(repository.getDefaultBranch())
                ? repository.getDefaultBranch()
                : "HEAD";
        JSONObject payload = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + repo + "/git/trees/" + branch)
                        .queryParam("recursive", "1")
                        .build())
                .headers(this::applyOptionalToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException("GitHub tree API failed: " + body)))
                .bodyToMono(JSONObject.class)
                .timeout(REQUEST_TIMEOUT)
                .block();

        JSONArray tree = payload == null ? null : payload.getJSONArray("tree");
        if (tree == null) {
            throw new IllegalStateException("GitHub tree API returned empty tree");
        }

        List<ManifestRef> manifests = new ArrayList<>();
        for (Object item : tree) {
            JSONObject node = toJsonObject(item);
            if (!"blob".equals(node.getString("type"))) {
                continue;
            }
            String path = node.getString("path");
            if (!isDependencyManifest(path) || isIgnoredPath(path)) {
                continue;
            }
            Long size = node.getLong("size");
            String url = node.getString("url");
            manifests.add(new ManifestRef(path, size == null ? 0 : size, url));
        }

        int directDependencyCount = 0;
        int downloadedManifestCount = 0;
        List<ManifestSummary> summaries = new ArrayList<>();
        for (ManifestRef manifest : manifests.stream()
                .sorted(Comparator.comparingInt(this::manifestPriority)
                        .thenComparing(ManifestRef::path))
                .limit(Math.max(constraints.getMaxManifestDownloads(), 0))
                .toList()) {
            if (manifest.size() > MAX_MANIFEST_BYTES || !StringUtils.hasText(manifest.url())) {
                summaries.add(new ManifestSummary(manifest.path(), 0, "skipped_large_or_missing_blob"));
                continue;
            }
            String content = fetchBlobContent(manifest.url());
            int dependencies = countDependencies(manifest.path(), content);
            directDependencyCount += dependencies;
            downloadedManifestCount++;
            summaries.add(new ManifestSummary(manifest.path(), dependencies, "parsed"));
        }

        DependencyProfile profile = new DependencyProfile();
        profile.setManifestCount(manifests.size());
        profile.setDownloadedManifestCount(downloadedManifestCount);
        profile.setDirectDependencyCount(directDependencyCount);
        profile.setManifests(summaries);
        return profile;
    }

    private String fetchBlobContent(String url) {
        JSONObject payload = webClient.get()
                .uri(url)
                .headers(this::applyOptionalToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new IllegalStateException("GitHub blob API failed: " + body)))
                .bodyToMono(JSONObject.class)
                .timeout(REQUEST_TIMEOUT)
                .block();
        String content = payload == null ? null : payload.getString("content");
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
    }

    private int countDependencies(String path, String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        String name = fileName(path).toLowerCase(Locale.ROOT);
        try {
            if ("package.json".equals(name) || "composer.json".equals(name) || "vcpkg.json".equals(name)) {
                return countJsonDependencies(content, name);
            }
            if ("pom.xml".equals(name)) {
                return countPomDependencies(content);
            }
            if ("go.mod".equals(name)) {
                return countGoModDependencies(content);
            }
            if ("cargo.toml".equals(name) || "pyproject.toml".equals(name)) {
                return countTomlDependencies(content, name);
            }
            if (name.startsWith("requirements") && name.endsWith(".txt")) {
                return countRequirementLines(content);
            }
            if ("gemfile".equals(name)) {
                return countRegex(content, "(?m)^\\s*gem\\s+['\"][^'\"]+['\"]");
            }
            if ("setup.cfg".equals(name)) {
                return countSetupCfgDependencies(content);
            }
            if (name.startsWith("build.gradle")) {
                return countGradleDependencies(content);
            }
            if ("conanfile.txt".equals(name)) {
                return countConanfileTxtDependencies(content);
            }
            if ("cmakelists.txt".equals(name)) {
                return countRegex(content, "(?im)^\\s*(find_package|FetchContent_Declare)\\s*\\(");
            }
        } catch (Exception e) {
            log.debug("Failed to count manifest dependencies, path={}", path, e);
        }
        return 0;
    }

    private int countJsonDependencies(String content, String name) {
        JSONObject json = JSON.parseObject(content);
        if ("vcpkg.json".equals(name)) {
            Object dependencies = json.get("dependencies");
            return dependencies instanceof JSONArray array ? array.size() : 0;
        }
        int count = 0;
        for (String key : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
            JSONObject deps = json.getJSONObject(key);
            if (deps != null) {
                count += deps.size();
            }
        }
        if ("composer.json".equals(name)) {
            JSONObject require = json.getJSONObject("require");
            JSONObject requireDev = json.getJSONObject("require-dev");
            count = nullSafeSize(require) + nullSafeSize(requireDev);
            if (require != null && require.containsKey("php")) {
                count--;
            }
        }
        return Math.max(count, 0);
    }

    private int countPomDependencies(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        NodeList dependencies = document.getElementsByTagName("dependency");
        if (dependencies == null) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < dependencies.getLength(); index++) {
            Node dependency = dependencies.item(index);
            Node dependenciesNode = dependency == null ? null : dependency.getParentNode();
            Node projectNode = dependenciesNode == null ? null : dependenciesNode.getParentNode();
            if (dependenciesNode != null
                    && projectNode != null
                    && "dependencies".equals(dependenciesNode.getNodeName())
                    && "project".equals(projectNode.getNodeName())) {
                count++;
            }
        }
        return count;
    }

    private int countGoModDependencies(String content) {
        int count = 0;
        boolean inRequireBlock = false;
        for (String line : content.split("\\R")) {
            String trimmed = stripComment(line).trim();
            if (trimmed.startsWith("require (")) {
                inRequireBlock = true;
                continue;
            }
            if (inRequireBlock && trimmed.startsWith(")")) {
                inRequireBlock = false;
                continue;
            }
            if (inRequireBlock && StringUtils.hasText(trimmed)) {
                count++;
            } else if (trimmed.startsWith("require ") && trimmed.split("\\s+").length >= 3) {
                count++;
            }
        }
        return count;
    }

    private int countTomlDependencies(String content, String name) {
        if ("cargo.toml".equals(name)) {
            return countTomlSectionKeys(content, Set.of(
                    "dependencies",
                    "dev-dependencies",
                    "build-dependencies"));
        }
        int count = countArrayItems(content, "(?s)(?:^|\\n)\\s*dependencies\\s*=\\s*\\[(.*?)]");
        count += countTomlSectionKeys(content, Set.of(
                "tool.poetry.dependencies",
                "tool.poetry.dev-dependencies",
                "project.optional-dependencies"));
        return count;
    }

    private int countTomlSectionKeys(String content, Set<String> sectionNames) {
        int count = 0;
        String currentSection = "";
        for (String line : content.split("\\R")) {
            String trimmed = stripTomlComment(line).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.replace("[", "").replace("]", "").trim();
                continue;
            }
            if (!sectionNames.contains(currentSection) || !trimmed.contains("=")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
            if (StringUtils.hasText(key) && !"python".equalsIgnoreCase(key)) {
                count++;
            }
        }
        return count;
    }

    private int countRequirementLines(String content) {
        int count = 0;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed)
                    || trimmed.startsWith("#")
                    || trimmed.startsWith("-r ")
                    || trimmed.startsWith("--")) {
                continue;
            }
            count++;
        }
        return count;
    }

    private int countSetupCfgDependencies(String content) {
        int count = 0;
        boolean inOptions = false;
        boolean inInstallRequires = false;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inOptions = "[options]".equalsIgnoreCase(trimmed);
                inInstallRequires = false;
                continue;
            }
            if (!inOptions) {
                continue;
            }
            if (trimmed.startsWith("install_requires")) {
                inInstallRequires = true;
                continue;
            }
            if (inInstallRequires) {
                if (trimmed.contains("=")) {
                    break;
                }
                if (StringUtils.hasText(trimmed) && !trimmed.startsWith("#")) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countGradleDependencies(String content) {
        return countRegex(content,
                "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\\s+['\"]");
    }

    private int countConanfileTxtDependencies(String content) {
        int count = 0;
        boolean inRequires = false;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inRequires = "[requires]".equalsIgnoreCase(trimmed);
                continue;
            }
            if (inRequires && StringUtils.hasText(trimmed) && !trimmed.startsWith("#")) {
                count++;
            }
        }
        return count;
    }

    private int countArrayItems(String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content);
        int count = 0;
        while (matcher.find()) {
            String body = matcher.group(1);
            for (String value : body.split(",")) {
                if (StringUtils.hasText(value.trim())) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countRegex(String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean isDependencyManifest(String path) {
        String name = fileName(path);
        return MANIFEST_NAMES.contains(name)
                || (name.startsWith("requirements") && name.endsWith(".txt"));
    }

    private boolean isIgnoredPath(String path) {
        if (!StringUtils.hasText(path)) {
            return true;
        }
        for (String part : path.toLowerCase(Locale.ROOT).split("/")) {
            if (IGNORED_PATH_PARTS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private int manifestPriority(ManifestRef manifest) {
        String path = manifest.path();
        String name = fileName(path);
        int depth = path.split("/").length;
        int base = depth == 1 ? 0 : depth * 10;
        if (Set.of("package.json", "pyproject.toml", "go.mod", "Cargo.toml", "pom.xml",
                "build.gradle", "composer.json", "Gemfile").contains(name)) {
            return base;
        }
        return base + 20;
    }

    private String fileName(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    private String stripComment(String line) {
        int index = line.indexOf("//");
        return index < 0 ? line : line.substring(0, index);
    }

    private String stripTomlComment(String line) {
        int index = line.indexOf('#');
        return index < 0 ? line : line.substring(0, index);
    }

    private int nullSafeSize(JSONObject object) {
        return object == null ? 0 : object.size();
    }

    private JSONObject toJsonObject(Object value) {
        if (value instanceof JSONObject object) {
            return object;
        }
        return JSON.parseObject(JSON.toJSONString(value));
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

    private org.springframework.web.reactive.function.client.WebClient buildWebClient() {
        org.springframework.web.reactive.function.client.WebClient.Builder builder =
                org.springframework.web.reactive.function.client.WebClient.builder()
                        .baseUrl(GITHUB_API_BASE_URL)
                        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                        .defaultHeader(HttpHeaders.USER_AGENT, "fly-agent-swe-pro")
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));
        applyProxyIfAvailable(builder);
        return builder.build();
    }

    private void applyProxyIfAvailable(org.springframework.web.reactive.function.client.WebClient.Builder builder) {
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

    @Data
    public static class ProfileConstraints {

        private Boolean enabled = true;

        private double minPrimaryLanguageRatio = 0.70d;

        private int maxLanguageCount = 4;

        private int maxDirectDependencies = 30;

        private int maxManifestCount = 8;

        private int maxManifestDownloads = 3;
    }

    @Data
    public static class RepoProfileDecision {

        private String repo;

        private boolean allowed;

        private String reasonCode;

        private String reason;

        private Integer score;

        private Double primaryLanguageRatio;

        private Integer languageCount;

        private Integer manifestCount;

        private Integer downloadedManifestCount;

        private Integer directDependencyCount;

        private List<LanguageShare> languages = List.of();

        private List<ManifestSummary> manifests = List.of();

        private String profileError;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("repo", repo);
            json.put("allowed", allowed);
            json.put("reasonCode", reasonCode);
            json.put("reason", reason);
            json.put("score", score);
            json.put("primaryLanguageRatio", primaryLanguageRatio);
            json.put("languageCount", languageCount);
            json.put("manifestCount", manifestCount);
            json.put("downloadedManifestCount", downloadedManifestCount);
            json.put("directDependencyCount", directDependencyCount);
            json.put("profileError", profileError);
            json.put("languages", languages == null ? List.of() : languages);
            json.put("manifests", manifests == null ? List.of() : manifests);
            return json;
        }
    }

    @Data
    private static class LanguageProfile {

        private double primaryLanguageRatio;

        private int effectiveLanguageCount;

        private List<LanguageShare> languages = List.of();
    }

    @Data
    private static class DependencyProfile {

        private int manifestCount;

        private int downloadedManifestCount;

        private int directDependencyCount;

        private List<ManifestSummary> manifests = List.of();
    }

    public record LanguageShare(String name, long bytes, double ratio) {
    }

    private record ManifestRef(String path, long size, String url) {
    }

    public record ManifestSummary(String path, int directDependencies, String status) {
    }
}
