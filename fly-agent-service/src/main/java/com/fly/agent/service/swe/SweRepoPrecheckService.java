package com.fly.agent.service.swe;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.dao.entity.swe.SweRepoBlacklistEntity;
import com.fly.agent.dao.entity.swe.SweRepoScaReportEntity;
import com.fly.agent.dao.mapper.swe.SweRepoBlacklistMapper;
import com.fly.agent.dao.mapper.swe.SweRepoScaReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Shared repository-level hard gates for SWE-Pro discovery.
 */
@Service
@RequiredArgsConstructor
public class SweRepoPrecheckService {

    private static final Set<String> REJECTED_SCA_STATUSES = Set.of("REJECT", "DENY", "DENIED", "BLOCK", "BLOCKED", "INCOMPATIBLE");

    private final SweRepoBlacklistMapper blacklistMapper;
    private final SweRepoScaReportMapper scaReportMapper;

    public RepoPrecheckDecision check(String repo) {
        String normalizedRepo = normalizeRepo(repo);
        if (!StringUtils.hasText(normalizedRepo)) {
            return RepoPrecheckDecision.reject(repo, "invalid_repo", "repo 格式无效");
        }
        SweRepoBlacklistEntity blacklist = blacklistMapper.selectOne(new LambdaQueryWrapper<SweRepoBlacklistEntity>()
                .eq(SweRepoBlacklistEntity::getRepo, normalizedRepo)
                .last("LIMIT 1"));
        if (blacklist != null) {
            return RepoPrecheckDecision.reject(
                    normalizedRepo,
                    "repo_blacklisted",
                    StringUtils.hasText(blacklist.getBenchmarks())
                            ? "命中已有 SWE 数据集黑名单: " + blacklist.getBenchmarks()
                            : "命中已有 SWE 数据集黑名单");
        }
        SweRepoScaReportEntity scaReport = scaReportMapper.selectOne(new LambdaQueryWrapper<SweRepoScaReportEntity>()
                .eq(SweRepoScaReportEntity::getRepo, normalizedRepo)
                .last("LIMIT 1"));
        if (scaReport != null && isRejectedScaStatus(scaReport.getCompatibilityStatus())) {
            String license = StringUtils.hasText(scaReport.getLicenseSpdxId())
                    ? scaReport.getLicenseSpdxId()
                    : scaReport.getLicenseName();
            String reason = StringUtils.hasText(scaReport.getCompatibilityReason())
                    ? scaReport.getCompatibilityReason()
                    : "SCA license compatibility rejected";
            if (StringUtils.hasText(license)) {
                reason = license + ": " + reason;
            }
            return RepoPrecheckDecision.reject(normalizedRepo, "sca_license_rejected", reason);
        }
        return RepoPrecheckDecision.allow(normalizedRepo);
    }

    public boolean allows(String repo) {
        return check(repo).allowed();
    }

    public static String normalizeRepo(String repo) {
        if (!StringUtils.hasText(repo)) {
            return null;
        }
        String normalized = repo.trim();
        if (normalized.startsWith("http://github.com/")) {
            normalized = normalized.substring("http://github.com/".length());
        } else if (normalized.startsWith("https://github.com/")) {
            normalized = normalized.substring("https://github.com/".length());
        } else if (normalized.startsWith("github.com/")) {
            normalized = normalized.substring("github.com/".length());
        }
        int queryIndex = firstPositiveIndex(normalized.indexOf('?'), normalized.indexOf('#'));
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return null;
        }
        normalized = parts[0] + "/" + parts[1];
        if (!normalized.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static int firstPositiveIndex(int left, int right) {
        if (left < 0) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        return Math.min(left, right);
    }

    private boolean isRejectedScaStatus(String status) {
        return StringUtils.hasText(status)
                && REJECTED_SCA_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    public record RepoPrecheckDecision(boolean allowed, String repo, String reasonCode, String reason) {

        public static RepoPrecheckDecision allow(String repo) {
            return new RepoPrecheckDecision(true, repo, "", "");
        }

        public static RepoPrecheckDecision reject(String repo, String reasonCode, String reason) {
            return new RepoPrecheckDecision(false, repo, reasonCode, reason);
        }
    }
}
