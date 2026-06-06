package com.fly.agent.service.swe;

import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Per-job GitHub token override. Job params can supply a token without writing
 * it to application config.
 */
public final class GithubTokenContext {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();
    private static final ThreadLocal<String> TOKEN_ID = new ThreadLocal<>();

    private GithubTokenContext() {
    }

    public static String currentToken() {
        return TOKEN.get();
    }

    public static String currentTokenId() {
        return TOKEN_ID.get();
    }

    public static <T> T withToken(String token, Supplier<T> action) {
        return withToken(null, token, action);
    }

    public static <T> T withToken(String tokenId, String token, Supplier<T> action) {
        String previous = TOKEN.get();
        String previousId = TOKEN_ID.get();
        if (StringUtils.hasText(token)) {
            TOKEN.set(token.trim());
        }
        if (StringUtils.hasText(tokenId)) {
            TOKEN_ID.set(tokenId.trim());
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TOKEN.remove();
            } else {
                TOKEN.set(previous);
            }
            if (previousId == null) {
                TOKEN_ID.remove();
            } else {
                TOKEN_ID.set(previousId);
            }
        }
    }
}
