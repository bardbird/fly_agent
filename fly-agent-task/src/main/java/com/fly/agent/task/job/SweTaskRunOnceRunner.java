package com.fly.agent.task.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Allows one-off local execution of task handlers without XXL-Job Admin.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SweTaskRunOnceRunner implements ApplicationRunner {

    private final SweRepoDiscoveryJob sweRepoDiscoveryJob;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${swe.task.run-once:}")
    private String runOnceJob;

    @Value("${swe.task.run-once-param:}")
    private String runOnceParam;

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(runOnceJob)) {
            return;
        }

        String message = switch (runOnceJob.trim()) {
            case "sweRepoBlacklistImportJob" -> sweRepoDiscoveryJob.importBlacklist(runOnceParam);
            case "sweRepoDiscoveryScanJob" -> sweRepoDiscoveryJob.scanRepositories(runOnceParam);
            case "sweScaAllowRepoCandidateScanJob" -> sweRepoDiscoveryJob.scanScaAllowedRepositories(runOnceParam);
            default -> throw new IllegalArgumentException("Unsupported run-once job: " + runOnceJob);
        };
        log.info("Run-once task finished, job={}, result={}", runOnceJob, message);
        SpringApplication.exit(applicationContext, () -> 0);
    }
}
