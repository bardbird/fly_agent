package com.fly.agent.service.swe;

import com.fly.agent.common.dto.swe.SweAllowedRepoDTO;
import com.fly.agent.common.dto.swe.SweAllowedRepoListResponse;
import com.fly.agent.common.dto.swe.SweScaReportGenerateRequest;
import com.fly.agent.common.dto.swe.SweScaReportGenerateResponse;
import com.fly.agent.common.exception.BusinessException;
import com.fly.agent.dao.mapper.swe.SweRepoScaReportMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates task-level SCA delivery materials for an existing SWE-Pro package.
 */
@Service
public class SweScaReportService {

    private static final String DELIVERY_DIR_NAME = "SCA_交付材料";
    private static final int DEFAULT_REPO_PAGE_SIZE = 20;
    private static final int MAX_REPO_PAGE_SIZE = 100;

    private final SweProperties properties;
    private final SweCommandRunner commandRunner;
    private final SweRepoScaReportMapper scaReportMapper;

    @Autowired
    public SweScaReportService(
            SweProperties properties,
            SweCommandRunner commandRunner,
            SweRepoScaReportMapper scaReportMapper) {
        this.properties = properties;
        this.commandRunner = commandRunner;
        this.scaReportMapper = scaReportMapper;
    }

    public SweScaReportService(SweProperties properties, SweCommandRunner commandRunner) {
        this(properties, commandRunner, null);
    }

    public SweScaReportGenerateResponse generate(SweScaReportGenerateRequest request) {
        Path packagePath = requirePackagePath(request);
        Path scriptPath = requireFile(toolkitScript("generate_task_sca.py"), "generate_task_sca.py is required");
        List<String> command = new ArrayList<>();
        command.add(StringUtils.hasText(properties.getPython()) ? properties.getPython() : "python3");
        command.add(scriptPath.toString());
        command.add("--package");
        command.add(packagePath.toString());
        if (StringUtils.hasText(request.getRawScanPath())) {
            command.add("--raw-scan");
            command.add(requireFile(Path.of(request.getRawScanPath()), "rawScanPath does not exist").toString());
            if (StringUtils.hasText(request.getScannerName())) {
                command.add("--scanner-name");
                command.add(request.getScannerName());
            }
        }
        if (StringUtils.hasText(request.getOutputDir())) {
            command.add("--output-dir");
            command.add(Path.of(request.getOutputDir()).toAbsolutePath().normalize().toString());
        }
        if (Boolean.TRUE.equals(request.getManifestOnly())) {
            command.add("--manifest-only");
        }

        SweCommandRunner.CommandResult result = commandRunner.run(
                "generate_task_sca",
                command,
                toolkitRoot(),
                logDir(packagePath),
                Map.of(),
                Duration.ofMinutes(10),
                false);
        Path outputDir = StringUtils.hasText(request.getOutputDir())
                ? requireGeneratedScaDirectory(Path.of(request.getOutputDir()).toAbsolutePath().normalize())
                : requireGeneratedScaDirectory(packagePath.resolve(DELIVERY_DIR_NAME));

        SweScaReportGenerateResponse response = new SweScaReportGenerateResponse();
        response.setPackagePath(packagePath.toString());
        response.setOutputDir(outputDir.toString());
        response.setSummary(result.getOutput());
        response.setGeneratedFiles(findGeneratedFiles(outputDir).stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList());
        return response;
    }

    public SweAllowedRepoListResponse listAllowedRepos(
            Integer page,
            Integer perPage,
            String language,
            Boolean inCandidate,
            String checkedFrom,
            String checkedTo) {
        requireScaReportMapper();
        int resolvedPage = page == null ? 1 : Math.max(page, 1);
        int resolvedPerPage = perPage == null
                ? DEFAULT_REPO_PAGE_SIZE
                : Math.min(Math.max(perPage, 1), MAX_REPO_PAGE_SIZE);
        String normalizedLanguage = normalizeLanguage(language);
        LocalDateTime checkedFromTime = parseCheckedDateStart(checkedFrom, "checkedFrom");
        LocalDateTime checkedToExclusive = parseCheckedDateEndExclusive(checkedTo, "checkedTo");
        if (checkedFromTime != null && checkedToExclusive != null && !checkedFromTime.isBefore(checkedToExclusive)) {
            throw new BusinessException("检查开始时间必须早于检查结束时间");
        }
        long total = scaReportMapper.countAllowedRepoReports(
                normalizedLanguage,
                inCandidate,
                checkedFromTime,
                checkedToExclusive);
        int totalPages = total == 0 ? 1 : (int) Math.ceil(total / (double) resolvedPerPage);
        int offset = (resolvedPage - 1) * resolvedPerPage;

        SweAllowedRepoListResponse response = new SweAllowedRepoListResponse();
        response.setPage(resolvedPage);
        response.setPerPage(resolvedPerPage);
        response.setTotal(total);
        response.setTotalPages(totalPages);
        response.setRepositories(scaReportMapper
                .selectAllowedRepoReports(
                        normalizedLanguage,
                        inCandidate,
                        checkedFromTime,
                        checkedToExclusive,
                        resolvedPerPage,
                        offset)
                .stream()
                .map(this::toAllowedRepoDTO)
                .toList());
        return response;
    }

    public String exportAllowedRepoCsv(
            String language,
            Boolean inCandidate,
            String checkedFrom,
            String checkedTo) {
        requireScaReportMapper();
        LocalDateTime checkedFromTime = parseCheckedDateStart(checkedFrom, "checkedFrom");
        LocalDateTime checkedToExclusive = parseCheckedDateEndExclusive(checkedTo, "checkedTo");
        StringBuilder csv = new StringBuilder("repo,github_url\n");
        for (Map<String, Object> row : scaReportMapper.selectAllowedRepoExportRows(
                normalizeLanguage(language),
                inCandidate,
                checkedFromTime,
                checkedToExclusive)) {
            String repo = stringValue(row.get("repo"));
            csv.append(csv(repo))
                    .append(',')
                    .append(csv(githubUrl(repo)))
                    .append('\n');
        }
        return csv.toString();
    }

    private Path requirePackagePath(SweScaReportGenerateRequest request) {
        if (request == null || !StringUtils.hasText(request.getPackagePath())) {
            throw new BusinessException("packagePath不能为空");
        }
        Path packagePath = requireDirectory(Path.of(request.getPackagePath()), "packagePath does not exist")
                .toAbsolutePath()
                .normalize();
        requireFile(packagePath.resolve("task.json"), "task.json is required");
        requireDirectory(packagePath.resolve("repo"), "repo directory is required");
        return packagePath;
    }

    private void requireScaReportMapper() {
        if (scaReportMapper == null) {
            throw new BusinessException("swe_repo_sca_report mapper is not available");
        }
    }

    private SweAllowedRepoDTO toAllowedRepoDTO(Map<String, Object> row) {
        String repo = stringValue(row.get("repo"));
        SweAllowedRepoDTO dto = new SweAllowedRepoDTO();
        dto.setId(longValue(row.get("id")));
        dto.setRepo(repo);
        dto.setGithubUrl(githubUrl(repo));
        dto.setPrimaryLanguage(stringValue(row.get("primaryLanguage")));
        dto.setGithubStars(intValue(row.get("githubStars")));
        dto.setLicenseSpdxId(stringValue(row.get("licenseSpdxId")));
        dto.setLicenseName(stringValue(row.get("licenseName")));
        dto.setCompatibilityReason(stringValue(row.get("compatibilityReason")));
        dto.setInCandidate(booleanValue(row.get("inCandidate")));
        dto.setCheckedAt(dateTimeValue(row.get("checkedAt")));
        dto.setCandidateLastScannedAt(dateTimeValue(row.get("candidateLastScannedAt")));
        return dto;
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language) || "all".equalsIgnoreCase(language.trim())) {
            return null;
        }
        String normalized = language.trim().toLowerCase();
        return switch (normalized) {
            case "ts" -> "typescript";
            case "js" -> "javascript";
            default -> normalized;
        };
    }

    private LocalDateTime parseCheckedDateStart(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed).atStartOfDay();
            }
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new BusinessException(fieldName + "格式无效，应为 yyyy-MM-dd 或 ISO 日期时间");
        }
    }

    private LocalDateTime parseCheckedDateEndExclusive(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed).plusDays(1).atStartOfDay();
            }
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new BusinessException(fieldName + "格式无效，应为 yyyy-MM-dd 或 ISO 日期时间");
        }
    }

    private String githubUrl(String repo) {
        return StringUtils.hasText(repo) ? "https://github.com/" + repo : "";
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            return Long.parseLong(String.valueOf(value));
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            return Integer.parseInt(String.valueOf(value));
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String dateTimeValue(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        return value == null ? null : String.valueOf(value);
    }

    private Path requireGeneratedScaDirectory(Path outputPath) {
        Path outputDir = requireDirectory(outputPath, "SCA delivery directory is required")
                .toAbsolutePath()
                .normalize();
        requireFile(outputDir.resolve("01_task_SCA报告.md"), "SCA markdown report is required");
        requireFile(outputDir.resolve("02_数据级SCA明细表.csv"), "SCA data detail CSV is required");
        requireFile(outputDir.resolve("03_开源组件与许可证清单.csv"), "SCA component CSV is required");
        requireFile(outputDir.resolve("07_风险数据清单.csv"), "SCA risk CSV is required");
        requireDirectory(outputDir.resolve("04_SBOM文件"), "SCA SBOM directory is required");
        requireDirectory(outputDir.resolve("05_原始扫描日志"), "SCA raw scan directory is required");
        if (findGeneratedFiles(outputDir.resolve("04_SBOM文件")).isEmpty()) {
            throw new BusinessException("SCA SBOM file is required");
        }
        if (findGeneratedFiles(outputDir.resolve("05_原始扫描日志")).isEmpty()) {
            throw new BusinessException("SCA raw scan evidence is required");
        }
        return outputDir;
    }

    private List<Path> findGeneratedFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, 8)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new BusinessException("failed to list SCA generated files: " + root, e);
        }
    }

    private Path logDir(Path packagePath) {
        return packagePath.resolve(".sca-api-logs");
    }

    private Path toolkitScript(String scriptName) {
        return toolkitRoot().resolve("scripts").resolve(scriptName);
    }

    private Path toolkitRoot() {
        return resolveLocalPath(properties.getToolkitRoot(), "tools/swe-pro-production");
    }

    private Path resolveLocalPath(String configuredPath, String fallbackPath) {
        String text = StringUtils.hasText(configuredPath) ? configuredPath : fallbackPath;
        Path path = Path.of(text);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Path current = cwd;
        while (current != null) {
            Path candidate = current.resolve(path).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return cwd.resolve(path).normalize();
    }

    private Path requireDirectory(Path path, String message) {
        if (!Files.isDirectory(path)) {
            throw new BusinessException(message + ": " + path);
        }
        return path;
    }

    private Path requireFile(Path path, String message) {
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(message + ": " + path);
        }
        return path;
    }
}
