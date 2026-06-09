package com.fly.agent.service.tb20;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.tb20.Tb20ConfigRequest;
import com.fly.agent.common.dto.tb20.Tb20ConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class Tb20ConfigService {

    private static final String REDIS_KEY = "fly-agent:tb20:config";

    private final StringRedisTemplate redisTemplate;
    private final Tb20Properties properties;

    public Tb20ConfigResponse get(Tb20ConfigRequest request) {
        String scope = normalizeScope(request.getScope());
        Object value = redisTemplate.opsForHash().get(REDIS_KEY, scope);
        JSONObject values = defaultValues(scope);
        if (value != null && StringUtils.hasText(value.toString())) {
            values.putAll(JSON.parseObject(value.toString()));
        }
        Tb20ConfigResponse response = new Tb20ConfigResponse();
        response.setScope(scope);
        response.setValues(values);
        return response;
    }

    public Tb20ConfigResponse save(Tb20ConfigRequest request) {
        String scope = normalizeScope(request.getScope());
        JSONObject values = request.getValues() == null ? new JSONObject() : request.getValues();
        redisTemplate.opsForHash().put(REDIS_KEY, scope, values.toJSONString());
        Tb20ConfigResponse response = new Tb20ConfigResponse();
        response.setScope(scope);
        response.setValues(values);
        return response;
    }

    private String normalizeScope(String scope) {
        return StringUtils.hasText(scope) ? scope.trim() : "dataset-production";
    }

    private JSONObject defaultValues(String scope) {
        JSONObject values = new JSONObject();
        Path productionRoot = productionRoot();
        if ("dataset-production".equals(scope)) {
            values.put("workspaceRoot", productionRoot.resolve("dataset-production-runs").toString());
            values.put("outputRoot", productionRoot.resolve("dataset-source").toString());
            values.put("defaultDomain", "software-engineering");
            values.put("defaultSourceChannel", "github-pr-mining");
            values.put("githubApiBase", "https://api.github.com");
            values.put("ghArchiveBase", "https://data.gharchive.org");
            values.put("githubTokenSource", "reuse-swe-pro-token-pool");
            values.put("sourceName", "");
            values.put("sourceUrl", "");
            values.put("license", "");
            values.put("licenseUrl", "");
            values.put("termsUrl", "");
            values.put("allowedForTaskGeneration", "false");
            values.put("adapterType", "codex");
            values.put("codexBinary", "codex");
            values.put("codexModel", "");
            values.put("codexProfile", "");
            values.put("codexSandbox", "danger-full-access");
            values.put("codexSkillSyncMode", "symlink");
            values.put("licenseAllowlist", "MIT,BSD-2-Clause,BSD-3-Clause,Apache-2.0,ISC,CC0,CC-BY-4.0");
            values.put("maxCandidates", "20");
            return values;
        }
        values.put("workspaceRoot", productionRoot.resolve("execution-runs").toString());
        values.put("outputRoot", productionRoot.resolve("delivery").toString());
        values.put("sourceRoot", firstText(properties.getDefaultSourceRoot(), ""));
        values.put("agent", "claude-code");
        values.put("concurrency", "1");
        values.put("failFast", "false");
        values.put("dockerRegistryMirrors", "");
        values.put("aptMirror", "");
        values.put("pythonIndexUrl", "https://pypi.tuna.tsinghua.edu.cn/simple");
        return values;
    }

    private Path productionRoot() {
        return resolveLocalPath(properties.getProductionRoot(), "tb20-output");
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

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }
}
