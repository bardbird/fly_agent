package com.fly.agent.service.swe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweCommandRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runRedactsBaseUrlInCommandLog() throws Exception {
        SweCommandRunner runner = new SweCommandRunner();
        Path logDir = tempDir.resolve("logs");

        SweCommandRunner.CommandResult result = runner.run(
                "model_eval",
                List.of(
                        "/bin/sh",
                        "-c",
                        "printf ok",
                        "--base-url",
                        "https://secret-base.example/v1",
                        "--agent.model.api_base=https://secret-agent.example/v1"
                ),
                tempDir,
                logDir,
                Map.of(),
                Duration.ofSeconds(5),
                false
        );

        String log = Files.readString(result.getLogPath(), StandardCharsets.UTF_8);
        assertFalse(log.contains("secret-base.example"));
        assertFalse(log.contains("secret-agent.example"));
        assertTrue(log.contains("--base-url [REDACTED_BASE_URL]"));
        assertTrue(log.contains("--agent.model.api_base=[REDACTED_BASE_URL]"));
        assertTrue(log.contains("ok"));
    }
}
