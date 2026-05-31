package com.fly.agent.service.swe;

import com.fly.agent.common.dto.swe.SweRuntimeSettingDTO;
import com.fly.agent.common.dto.swe.SweRuntimeSettingsRequest;
import com.fly.agent.common.dto.swe.SweRuntimeSettingsResponse;
import com.fly.agent.service.properties.ZhipuProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stores SWE-Pro runtime tokens and API keys in Redis.
 */
@Service
@RequiredArgsConstructor
public class SweRuntimeSettingsService {

    public static final String KEY_GITHUB_TOKEN = "githubToken";
    public static final String KEY_ZHIPU_API_KEY = "zhipuApiKey";
    public static final String KEY_QWEN_BASE_URL = "qwenBaseUrl";
    public static final String KEY_QWEN_TOKEN = "qwenToken";
    public static final String KEY_QWEN_MODEL = "qwenModel";
    public static final String KEY_OPUS_BASE_URL = "opusBaseUrl";
    public static final String KEY_OPUS_TOKEN = "opusToken";
    public static final String KEY_OPUS_MODEL = "opusModel";
    public static final String KEY_QWEN_ATTEMPTS = "qwenAttempts";
    public static final String KEY_OPUS_ATTEMPTS = "opusAttempts";
    public static final String KEY_SWE_AGENT_MAX_STEPS = "sweAgentMaxSteps";
    public static final String KEY_QWEN_MAX_STEPS_SCHEDULE = "qwenMaxStepsSchedule";
    public static final String KEY_OPUS_MAX_STEPS_SCHEDULE = "opusMaxStepsSchedule";
    public static final String KEY_MODEL_TIMEOUT_SECONDS = "modelTimeoutSeconds";

    private static final String REDIS_KEY = "fly-agent:swe:runtime-settings";
    private static final String UPDATED_AT_FIELD = "_updatedAt";

    private static final Map<String, SettingDefinition> DEFINITIONS = buildDefinitions();

    private final StringRedisTemplate redisTemplate;
    private final SweProperties sweProperties;
    private final ZhipuProperties zhipuProperties;

    public SweRuntimeSettingsResponse getSettings() {
        List<SweRuntimeSettingDTO> settings = new ArrayList<>();
        for (SettingDefinition definition : DEFINITIONS.values()) {
            String value = resolveValue(definition.key());
            boolean configured = StringUtils.hasText(value);
            settings.add(new SweRuntimeSettingDTO(
                    definition.key(),
                    definition.label(),
                    definition.secret() ? null : value,
                    definition.secret() ? maskSecret(value) : null,
                    configured,
                    definition.secret(),
                    definition.description()));
        }
        return new SweRuntimeSettingsResponse(settings);
    }

    public SweRuntimeSettingsResponse saveSettings(SweRuntimeSettingsRequest request) {
        if (request == null || request.getValues() == null || request.getValues().isEmpty()) {
            return getSettings();
        }
        Map<String, String> updates = new LinkedHashMap<>();
        request.getValues().forEach((key, value) -> {
            SettingDefinition definition = DEFINITIONS.get(key);
            if (definition == null || !StringUtils.hasText(value)) {
                return;
            }
            String normalized = value.trim();
            if (definition.secret() && isMaskedPlaceholder(normalized)) {
                return;
            }
            updates.put(key, normalized);
        });
        if (!updates.isEmpty()) {
            updates.put(UPDATED_AT_FIELD, Instant.now().toString());
            redisTemplate.opsForHash().putAll(REDIS_KEY, updates);
        }
        return getSettings();
    }

    public String resolveGithubToken(String fallback) {
        String value = getStoredValue(KEY_GITHUB_TOKEN);
        return StringUtils.hasText(value) ? value : fallback;
    }

    public String resolveZhipuApiKey(String fallback) {
        String value = getStoredValue(KEY_ZHIPU_API_KEY);
        return StringUtils.hasText(value) ? value : fallback;
    }

    public SweProperties.Model resolveQwenModel() {
        return resolveModel(
                sweProperties.getQwen(),
                KEY_QWEN_BASE_URL,
                KEY_QWEN_TOKEN,
                KEY_QWEN_MODEL);
    }

    public SweProperties.Model resolveOpusModel() {
        return resolveModel(
                sweProperties.getOpus(),
                KEY_OPUS_BASE_URL,
                KEY_OPUS_TOKEN,
                KEY_OPUS_MODEL);
    }

    public int resolveQwenAttempts() {
        return resolvePositiveInteger(KEY_QWEN_ATTEMPTS, sweProperties.getQwenAttempts(), 4);
    }

    public int resolveOpusAttempts() {
        return resolvePositiveInteger(KEY_OPUS_ATTEMPTS, sweProperties.getOpusAttempts(), 8);
    }

    public int resolveSweAgentMaxSteps() {
        Integer fallback = sweProperties.getSweAgent() == null ? null : sweProperties.getSweAgent().getMaxSteps();
        return resolvePositiveInteger(KEY_SWE_AGENT_MAX_STEPS, fallback, 20);
    }

    public String resolveOpusMaxStepsSchedule() {
        String value = getStoredValue(KEY_OPUS_MAX_STEPS_SCHEDULE);
        if (StringUtils.hasText(value) && isValidPositiveIntegerList(value)) {
            return value.trim();
        }
        return sweProperties.getOpusMaxStepsSchedule();
    }

    public String resolveQwenMaxStepsSchedule() {
        String value = getStoredValue(KEY_QWEN_MAX_STEPS_SCHEDULE);
        if (StringUtils.hasText(value) && isValidPositiveIntegerList(value)) {
            return value.trim();
        }
        return sweProperties.getQwenMaxStepsSchedule();
    }

    public int resolveModelTimeoutSeconds() {
        return resolvePositiveInteger(KEY_MODEL_TIMEOUT_SECONDS, sweProperties.getModelTimeoutSeconds(), 3600);
    }

    private SweProperties.Model resolveModel(
            SweProperties.Model fallback,
            String baseUrlKey,
            String tokenKey,
            String modelKey) {
        SweProperties.Model source = fallback == null ? new SweProperties.Model() : fallback;
        SweProperties.Model model = new SweProperties.Model();
        model.setBaseUrl(resolve(baseUrlKey, source.getBaseUrl()));
        model.setToken(resolve(tokenKey, source.getToken()));
        model.setModel(resolve(modelKey, source.getModel()));
        model.setProvider(source.getProvider());
        model.setMaxTokens(source.getMaxTokens());
        model.setMaxInputTokens(source.getMaxInputTokens());
        model.setTemperature(source.getTemperature());
        return model;
    }

    private String resolve(String key, String fallback) {
        String value = getStoredValue(key);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private int resolvePositiveInteger(String key, Integer fallback, int defaultValue) {
        String value = getStoredValue(key);
        if (StringUtils.hasText(value)) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to property/default fallback.
            }
        }
        return fallback == null || fallback <= 0 ? defaultValue : fallback;
    }

    private String resolveValue(String key) {
        String stored = getStoredValue(key);
        if (StringUtils.hasText(stored)) {
            return stored;
        }
        return switch (key) {
            case KEY_GITHUB_TOKEN -> fallbackGithubToken();
            case KEY_ZHIPU_API_KEY -> zhipuProperties.getApiKey();
            case KEY_QWEN_BASE_URL -> sweProperties.getQwen() == null ? null : sweProperties.getQwen().getBaseUrl();
            case KEY_QWEN_TOKEN -> sweProperties.getQwen() == null ? null : sweProperties.getQwen().getToken();
            case KEY_QWEN_MODEL -> sweProperties.getQwen() == null ? null : sweProperties.getQwen().getModel();
            case KEY_OPUS_BASE_URL -> sweProperties.getOpus() == null ? null : sweProperties.getOpus().getBaseUrl();
            case KEY_OPUS_TOKEN -> sweProperties.getOpus() == null ? null : sweProperties.getOpus().getToken();
            case KEY_OPUS_MODEL -> sweProperties.getOpus() == null ? null : sweProperties.getOpus().getModel();
            case KEY_QWEN_ATTEMPTS -> Objects.toString(resolveQwenAttempts(), null);
            case KEY_OPUS_ATTEMPTS -> Objects.toString(resolveOpusAttempts(), null);
            case KEY_SWE_AGENT_MAX_STEPS -> Objects.toString(resolveSweAgentMaxSteps(), null);
            case KEY_QWEN_MAX_STEPS_SCHEDULE -> resolveQwenMaxStepsSchedule();
            case KEY_OPUS_MAX_STEPS_SCHEDULE -> resolveOpusMaxStepsSchedule();
            case KEY_MODEL_TIMEOUT_SECONDS -> Objects.toString(resolveModelTimeoutSeconds(), null);
            default -> null;
        };
    }

    private String fallbackGithubToken() {
        if (sweProperties.getGithub() != null && StringUtils.hasText(sweProperties.getGithub().getToken())) {
            return sweProperties.getGithub().getToken();
        }
        String token = System.getenv("GITHUB_TOKEN");
        return StringUtils.hasText(token) ? token : System.getenv("GH_TOKEN");
    }

    private String getStoredValue(String key) {
        Object value = redisTemplate.opsForHash().get(REDIS_KEY, key);
        return value == null ? null : Objects.toString(value, null);
    }

    private static String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= 8) {
            return "********";
        }
        return normalized.substring(0, 4) + "..." + normalized.substring(normalized.length() - 4);
    }

    private static boolean isMaskedPlaceholder(String value) {
        return value.contains("...") || value.matches("\\*+");
    }

    private static boolean isValidPositiveIntegerList(String value) {
        String[] parts = value.split(",");
        boolean found = false;
        for (String part : parts) {
            String normalized = part.trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            try {
                if (Integer.parseInt(normalized) <= 0) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static Map<String, SettingDefinition> buildDefinitions() {
        Map<String, SettingDefinition> definitions = new LinkedHashMap<>();
        definitions.put(KEY_GITHUB_TOKEN, new SettingDefinition(KEY_GITHUB_TOKEN, "GitHub Token", true,
                "GitHub repository search and PR collection"));
        definitions.put(KEY_ZHIPU_API_KEY, new SettingDefinition(KEY_ZHIPU_API_KEY, "智谱 API Key", true,
                "GLM chat model key"));
        definitions.put(KEY_QWEN_BASE_URL, new SettingDefinition(KEY_QWEN_BASE_URL, "Qwen Base URL", false,
                "SWE-Pro Qwen evaluation endpoint"));
        definitions.put(KEY_QWEN_TOKEN, new SettingDefinition(KEY_QWEN_TOKEN, "Qwen API Key", true,
                "SWE-Pro Qwen pass gate"));
        definitions.put(KEY_QWEN_MODEL, new SettingDefinition(KEY_QWEN_MODEL, "Qwen Model", false,
                "SWE-Pro Qwen model name"));
        definitions.put(KEY_OPUS_BASE_URL, new SettingDefinition(KEY_OPUS_BASE_URL, "Opus Base URL", false,
                "SWE-Pro Opus evaluation endpoint"));
        definitions.put(KEY_OPUS_TOKEN, new SettingDefinition(KEY_OPUS_TOKEN, "Opus API Key", true,
                "SWE-Pro Opus pass gate"));
        definitions.put(KEY_OPUS_MODEL, new SettingDefinition(KEY_OPUS_MODEL, "Opus Model", false,
                "SWE-Pro Opus model name"));
        definitions.put(KEY_QWEN_ATTEMPTS, new SettingDefinition(KEY_QWEN_ATTEMPTS, "Qwen Attempts", false,
                "Qwen evaluation attempt count, default 4"));
        definitions.put(KEY_OPUS_ATTEMPTS, new SettingDefinition(KEY_OPUS_ATTEMPTS, "Opus Attempts", false,
                "Opus evaluation attempt count, default 8"));
        definitions.put(KEY_SWE_AGENT_MAX_STEPS, new SettingDefinition(KEY_SWE_AGENT_MAX_STEPS, "SWE-agent Max Steps", false,
                "Base per-attempt step limit for model evaluation"));
        definitions.put(KEY_QWEN_MAX_STEPS_SCHEDULE, new SettingDefinition(KEY_QWEN_MAX_STEPS_SCHEDULE, "Qwen Max Steps Schedule", false,
                "Comma-separated Qwen step gradient, e.g. 100,80,10,10; last value repeats"));
        definitions.put(KEY_OPUS_MAX_STEPS_SCHEDULE, new SettingDefinition(KEY_OPUS_MAX_STEPS_SCHEDULE, "Opus Max Steps Schedule", false,
                "Comma-separated Opus step gradient, e.g. 180,50,10; last value repeats"));
        definitions.put(KEY_MODEL_TIMEOUT_SECONDS, new SettingDefinition(KEY_MODEL_TIMEOUT_SECONDS, "Model Timeout Seconds", false,
                "Model evaluation timeout per command"));
        return definitions;
    }

    private record SettingDefinition(String key, String label, boolean secret, String description) {
    }
}
