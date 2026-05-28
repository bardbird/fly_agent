package com.fly.agent.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.dao.entity.swe.SweRepoScanCursorEntity;
import com.fly.agent.dao.mapper.swe.SweRepoScanCursorMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Persists star-based scan cursors so recurring discovery jobs do not keep
 * starting from the same high-star repositories.
 */
@Service
@RequiredArgsConstructor
public class SweRepoScanCursorService {

    private final SweRepoScanCursorMapper scanCursorMapper;

    @PostConstruct
    public void initializeSchema() {
        // Schema is managed by Flyway migration V6__swe_repo_precheck_tables.sql.
    }

    public ScanCursor getOrCreate(String language, String keyword, int minStars, Integer initialMaxStars) {
        initializeSchema();
        String cursorKey = cursorKey(language, keyword, minStars, initialMaxStars);
        SweRepoScanCursorEntity initial = new SweRepoScanCursorEntity();
        initial.setCursorKey(cursorKey);
        initial.setLanguage(normalizeLanguage(language));
        initial.setKeyword(normalizeKeyword(keyword));
        initial.setMinStars(minStars);
        initial.setInitialMaxStars(initialMaxStars);
        initial.setCurrentMaxStars(initialMaxStars);
        initial.setExhausted(false);
        scanCursorMapper.insertIgnoreInitial(initial);

        SweRepoScanCursorEntity existing = scanCursorMapper.selectOne(new LambdaQueryWrapper<SweRepoScanCursorEntity>()
                .eq(SweRepoScanCursorEntity::getCursorKey, cursorKey)
                .last("LIMIT 1"));
        if (existing == null) {
            throw new IllegalStateException("Failed to initialize SWE repo scan cursor: " + cursorKey);
        }
        return toCursor(existing);
    }

    public void advance(
            String language,
            String keyword,
            int minStars,
            Integer initialMaxStars,
            Integer lastMinSeenStars,
            String summary) {
        initializeSchema();
        String cursorKey = cursorKey(language, keyword, minStars, initialMaxStars);
        Integer nextMaxStars = lastMinSeenStars == null ? null : lastMinSeenStars - 1;
        boolean exhausted = lastMinSeenStars == null || nextMaxStars < minStars;
        SweRepoScanCursorEntity entity = new SweRepoScanCursorEntity();
        entity.setCursorKey(cursorKey);
        entity.setLanguage(normalizeLanguage(language));
        entity.setKeyword(normalizeKeyword(keyword));
        entity.setMinStars(minStars);
        entity.setInitialMaxStars(initialMaxStars);
        entity.setCurrentMaxStars(exhausted ? initialMaxStars : nextMaxStars);
        entity.setLastMinSeenStars(lastMinSeenStars);
        entity.setExhausted(exhausted);
        entity.setLastSummary(limitSummary(summary));
        scanCursorMapper.upsertProgress(entity);
    }

    public void reset(String language, String keyword, int minStars, Integer initialMaxStars) {
        initializeSchema();
        scanCursorMapper.deleteByCursorKey(cursorKey(language, keyword, minStars, initialMaxStars));
    }

    private ScanCursor toCursor(SweRepoScanCursorEntity entity) {
        ScanCursor cursor = new ScanCursor();
        cursor.setCursorKey(entity.getCursorKey());
        cursor.setLanguage(entity.getLanguage());
        cursor.setKeyword(entity.getKeyword());
        cursor.setMinStars(entity.getMinStars() == null ? 0 : entity.getMinStars());
        cursor.setInitialMaxStars(entity.getInitialMaxStars());
        cursor.setCurrentMaxStars(entity.getCurrentMaxStars());
        cursor.setLastMinSeenStars(entity.getLastMinSeenStars());
        cursor.setExhausted(Boolean.TRUE.equals(entity.getExhausted()));
        cursor.setLastSummary(entity.getLastSummary());
        return cursor;
    }

    private String cursorKey(String language, String keyword, int minStars, Integer initialMaxStars) {
        String maxStarsPart = initialMaxStars == null ? "*" : initialMaxStars.toString();
        String keywordPart = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : "";
        return normalizeLanguage(language) + "|" + keywordPart + "|" + minStars + "|" + maxStarsPart;
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "";
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

    private String limitSummary(String summary) {
        if (summary == null || summary.length() <= 1000) {
            return summary;
        }
        return summary.substring(0, 1000);
    }

    @Data
    public static class ScanCursor {

        private String cursorKey;

        private String language;

        private String keyword;

        private int minStars;

        private Integer initialMaxStars;

        private Integer currentMaxStars;

        private Integer lastMinSeenStars;

        private boolean exhausted;

        private String lastSummary;
    }
}
