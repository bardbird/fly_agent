package com.fly.agent.service.swe;

import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Per-job GitHub token override. Job params can supply a token without writing
 * it to application config.
 */
public final class GithubTokenContext {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private GithubTokenContext() {
    }

    public static String currentToken() {
        return TOKEN.get();
    }

    public static <T> T withToken(String token, Supplier<T> action) {
        String previous = TOKEN.get();
        if (StringUtils.hasText(token)) {
            TOKEN.set(token.trim());
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TOKEN.remove();
            } else {
                TOKEN.set(previous);
            }
        }
    }
}
