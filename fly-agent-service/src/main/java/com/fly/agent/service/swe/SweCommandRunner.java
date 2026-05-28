package com.fly.agent.service.swe;

import com.fly.agent.common.exception.BusinessException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs local production commands and stores logs under the current pipeline run.
 */
@Component
@RequiredArgsConstructor
public class SweCommandRunner {

    private static final String REDACTED_BASE_URL = "[REDACTED_BASE_URL]";
    private static final Pattern BASE_URL_EQUALS_ARG = Pattern.compile("--base-url=.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_API_BASE_ARG = Pattern.compile("--agent\\.model\\.api_base=.*", Pattern.CASE_INSENSITIVE);

    public CommandResult run(
            String name,
            List<String> command,
            Path cwd,
            Path logDir,
            Map<String, String> extraEnv,
            Duration timeout,
            boolean allowFailure) {
        try {
            Files.createDirectories(logDir);
            Path logPath = logDir.resolve(safeLogName(name) + ".log");
            Files.writeString(logPath, "$ " + String.join(" ", redactCommandForLog(command))
                    + System.lineSeparator()
                    + "cwd=" + cwd.toAbsolutePath()
                    + System.lineSeparator()
                    + System.lineSeparator(), StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            Map<String, String> env = builder.environment();
            extraEnv.forEach((key, value) -> {
                if (StringUtils.hasText(value)) {
                    env.put(key, value);
                }
            });

            long started = System.nanoTime();
            Process process = builder.start();
            boolean completed = timeout == null
                    ? waitWithoutTimeout(process)
                    : process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new BusinessException("command timeout: " + name);
            }
            int exitCode = process.exitValue();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Files.writeString(logPath, System.lineSeparator()
                    + "exitCode=" + exitCode
                    + System.lineSeparator()
                    + "elapsedMs=" + elapsedMs
                    + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            String output = Files.readString(logPath, StandardCharsets.UTF_8);
            if (exitCode != 0 && !allowFailure) {
                throw new BusinessException("command failed: " + name + ", exitCode=" + exitCode + ", log=" + logPath);
            }
            CommandResult result = new CommandResult();
            result.setName(name);
            result.setExitCode(exitCode);
            result.setOutput(output);
            result.setLogPath(logPath);
            result.setElapsedMs(elapsedMs);
            return result;
        } catch (IOException e) {
            throw new BusinessException("failed to run command: " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("command interrupted: " + name, e);
        }
    }

    private String safeLogName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private List<String> redactCommandForLog(List<String> command) {
        List<String> redacted = new ArrayList<>(command.size());
        boolean redactNext = false;
        for (String part : command) {
            if (redactNext) {
                redacted.add(REDACTED_BASE_URL);
                redactNext = false;
                continue;
            }
            if ("--base-url".equals(part)) {
                redacted.add(part);
                redactNext = true;
                continue;
            }
            if (BASE_URL_EQUALS_ARG.matcher(part).matches()) {
                redacted.add("--base-url=" + REDACTED_BASE_URL);
                continue;
            }
            if (MODEL_API_BASE_ARG.matcher(part).matches()) {
                redacted.add("--agent.model.api_base=" + REDACTED_BASE_URL);
                continue;
            }
            redacted.add(part);
        }
        return redacted;
    }

    private boolean waitWithoutTimeout(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    @Data
    public static class CommandResult {
        private String name;
        private Integer exitCode;
        private String output;
        private Path logPath;
        private Long elapsedMs;
    }
}
