package com.fly.agent.service.swe;

import com.alibaba.fastjson2.JSON;
import com.fly.agent.common.dto.swe.GithubTokenPoolItemDTO;
import com.fly.agent.common.dto.swe.GithubTokenPoolResponse;
import com.fly.agent.common.exception.BusinessException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Redis-backed GitHub token pool with per-job leases and daily failure state.
 */
@Slf4j
@Service
public class GithubTokenPoolService {

    private static final String TOKEN_HASH_KEY = "fly-agent:swe:github-token-pool:tokens";
    private static final String CURSOR_KEY = "fly-agent:swe:github-token-pool:cursor";
    private static final String LEASE_KEY_PREFIX = "fly-agent:swe:github-token-pool:lease:";
    private static final Duration LEASE_TTL = Duration.ofMinutes(45);

    private final StringRedisTemplate redisTemplate;

    public GithubTokenPoolService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public GithubTokenPoolResponse listTokens() {
        List<TokenRecord> records = records();
        List<GithubTokenPoolItemDTO> items = records.stream()
                .map(this::toDto)
                .toList();
        int availableCount = 0;
        int inUseCount = 0;
        int unavailableTodayCount = 0;
        for (GithubTokenPoolItemDTO item : items) {
            if (Boolean.TRUE.equals(item.getAvailable())) {
                availableCount++;
            }
            if (Boolean.TRUE.equals(item.getInUse())) {
                inUseCount++;
            }
            if (Boolean.TRUE.equals(item.getUnavailableToday())) {
                unavailableTodayCount++;
            }
        }
        return new GithubTokenPoolResponse(items, items.size(), availableCount, inUseCount, unavailableTodayCount);
    }

    public GithubTokenPoolResponse addTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return listTokens();
        }
        Instant now = Instant.now();
        for (String token : tokens) {
            String normalized = normalizeToken(token);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            String id = tokenId(normalized);
            TokenRecord existing = readRecord(id);
            TokenRecord record = existing == null ? new TokenRecord() : existing;
            record.setId(id);
            record.setToken(normalized);
            record.setEnabled(record.getEnabled() == null ? Boolean.TRUE : record.getEnabled());
            if (!StringUtils.hasText(record.getCreatedAt())) {
                record.setCreatedAt(now.toString());
            }
            record.setUpdatedAt(now.toString());
            saveRecord(record);
        }
        return listTokens();
    }

    public GithubTokenPoolResponse deleteTokens(List<String> ids) {
        for (String id : normalizeIds(ids)) {
            redisTemplate.opsForHash().delete(TOKEN_HASH_KEY, id);
            redisTemplate.delete(leaseKey(id));
        }
        return listTokens();
    }

    public GithubTokenPoolResponse enableTokens(List<String> ids, boolean enabled) {
        Instant now = Instant.now();
        for (String id : normalizeIds(ids)) {
            TokenRecord record = readRecord(id);
            if (record == null) {
                continue;
            }
            record.setEnabled(enabled);
            record.setUpdatedAt(now.toString());
            if (!enabled) {
                redisTemplate.delete(leaseKey(id));
            }
            saveRecord(record);
        }
        return listTokens();
    }

    public GithubTokenPoolResponse resetTodayStatus(List<String> ids) {
        Instant now = Instant.now();
        for (String id : normalizeIds(ids)) {
            TokenRecord record = readRecord(id);
            if (record == null) {
                continue;
            }
            record.setUnavailableDate(null);
            record.setUnavailableAt(null);
            record.setUnavailableReason(null);
            record.setUpdatedAt(now.toString());
            saveRecord(record);
        }
        return listTokens();
    }

    public TokenLease acquire(String owner) {
        List<TokenRecord> records = records().stream()
                .filter(record -> StringUtils.hasText(record.getToken()))
                .toList();
        if (records.isEmpty()) {
            throw new BusinessException("Redis 中没有配置可用 GitHub token");
        }

        long cursor = Objects.requireNonNullElse(redisTemplate.opsForValue().increment(CURSOR_KEY), 1L);
        int size = records.size();
        int start = Math.floorMod((int) cursor - 1, size);
        String normalizedOwner = StringUtils.hasText(owner) ? owner.trim() : "sweRepoJob";
        Instant now = Instant.now();
        for (int offset = 0; offset < size; offset++) {
            TokenRecord record = records.get((start + offset) % size);
            if (!isTokenAvailableForLease(record)) {
                continue;
            }
            String leaseKey = leaseKey(record.getId());
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(leaseKey, normalizedOwner, LEASE_TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                continue;
            }
            record.setInUseBy(normalizedOwner);
            record.setLeasedUntil(now.plus(LEASE_TTL).toString());
            record.setLastUsedAt(now.toString());
            record.setUpdatedAt(now.toString());
            saveRecord(record);
            log.info("Acquired GitHub token lease id={}, owner={}", record.getId(), normalizedOwner);
            return new TokenLease(record.getId(), record.getToken(), normalizedOwner);
        }
        throw new BusinessException("Redis 中没有可用且未占用的 GitHub token");
    }

    public void release(TokenLease lease) {
        if (lease == null || !StringUtils.hasText(lease.getId())) {
            return;
        }
        String leaseKey = leaseKey(lease.getId());
        String owner = redisTemplate.opsForValue().get(leaseKey);
        if (owner == null || owner.equals(lease.getOwner())) {
            redisTemplate.delete(leaseKey);
        }
        TokenRecord record = readRecord(lease.getId());
        if (record != null) {
            record.setInUseBy(null);
            record.setLeasedUntil(null);
            record.setUpdatedAt(Instant.now().toString());
            saveRecord(record);
        }
        log.info("Released GitHub token lease id={}, owner={}", lease.getId(), lease.getOwner());
    }

    public void markCurrentTokenUnavailableIfGithubFailure(HttpStatusCode statusCode, String message) {
        int status = statusCode == null ? 0 : statusCode.value();
        if (!isGithubCredentialOrRateFailure(status, message)) {
            return;
        }
        String id = GithubTokenContext.currentTokenId();
        if (!StringUtils.hasText(id)) {
            id = tokenId(GithubTokenContext.currentToken());
        }
        markUnavailableToday(id, message);
    }

    public void markUnavailableToday(String id, String reason) {
        if (!StringUtils.hasText(id)) {
            return;
        }
        TokenRecord record = readRecord(id.trim());
        if (record == null) {
            return;
        }
        Instant now = Instant.now();
        record.setUnavailableDate(LocalDate.now().toString());
        record.setUnavailableAt(now.toString());
        record.setUnavailableReason(limitReason(reason));
        record.setUpdatedAt(now.toString());
        saveRecord(record);
        redisTemplate.delete(leaseKey(record.getId()));
        log.warn("Marked GitHub token unavailable today, id={}, reason={}", record.getId(), limitReason(reason));
    }

    private GithubTokenPoolItemDTO toDto(TokenRecord record) {
        String owner = redisTemplate.opsForValue().get(leaseKey(record.getId()));
        boolean inUse = StringUtils.hasText(owner);
        boolean unavailableToday = isUnavailableToday(record);
        boolean enabled = !Boolean.FALSE.equals(record.getEnabled());
        boolean available = enabled && !inUse && !unavailableToday;
        return new GithubTokenPoolItemDTO(
                record.getId(),
                maskSecret(record.getToken()),
                enabled,
                available,
                inUse,
                unavailableToday,
                inUse ? owner : null,
                inUse ? record.getLeasedUntil() : null,
                record.getLastUsedAt(),
                record.getUnavailableAt(),
                record.getUnavailableReason(),
                record.getUpdatedAt());
    }

    private boolean isTokenAvailableForLease(TokenRecord record) {
        if (record == null || !StringUtils.hasText(record.getId()) || !StringUtils.hasText(record.getToken())) {
            return false;
        }
        if (Boolean.FALSE.equals(record.getEnabled()) || isUnavailableToday(record)) {
            return false;
        }
        return !StringUtils.hasText(redisTemplate.opsForValue().get(leaseKey(record.getId())));
    }

    private boolean isUnavailableToday(TokenRecord record) {
        return record != null && LocalDate.now().toString().equals(record.getUnavailableDate());
    }

    private List<TokenRecord> records() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(TOKEN_HASH_KEY);
        List<TokenRecord> records = new ArrayList<>();
        for (Object value : entries.values()) {
            if (value == null) {
                continue;
            }
            try {
                TokenRecord record = JSON.parseObject(Objects.toString(value), TokenRecord.class);
                if (record != null && StringUtils.hasText(record.getId())) {
                    records.add(record);
                }
            } catch (Exception ignored) {
                // Skip malformed legacy records.
            }
        }
        records.sort(Comparator.comparing(
                record -> StringUtils.hasText(record.getCreatedAt()) ? record.getCreatedAt() : record.getId()));
        return records;
    }

    private TokenRecord readRecord(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        Object value = redisTemplate.opsForHash().get(TOKEN_HASH_KEY, id.trim());
        if (value == null) {
            return null;
        }
        return JSON.parseObject(Objects.toString(value), TokenRecord.class);
    }

    private void saveRecord(TokenRecord record) {
        redisTemplate.opsForHash().put(TOKEN_HASH_KEY, record.getId(), JSON.toJSONString(record));
    }

    private String leaseKey(String id) {
        return LEASE_KEY_PREFIX + id;
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        return token.trim();
    }

    private String tokenId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isGithubCredentialOrRateFailure(int status, String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return status == 401
                || status == 403
                || normalized.contains("rate limit")
                || normalized.contains("bad credentials")
                || normalized.contains("requires authentication")
                || normalized.contains("resource not accessible")
                || normalized.contains("must have admin rights");
    }

    private static String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= 12) {
            return "********";
        }
        return normalized.substring(0, 6) + "..." + normalized.substring(normalized.length() - 6);
    }

    private static String limitReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String normalized = reason.trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    @Data
    private static class TokenRecord {

        private String id;

        private String token;

        private Boolean enabled;

        private String createdAt;

        private String updatedAt;

        private String lastUsedAt;

        private String inUseBy;

        private String leasedUntil;

        private String unavailableDate;

        private String unavailableAt;

        private String unavailableReason;
    }

    @Data
    public static class TokenLease {

        private final String id;

        private final String token;

        private final String owner;
    }
}
