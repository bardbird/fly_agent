package com.fly.agent.service.swe;

import com.fly.agent.dao.entity.swe.SweRepoBlacklistEntity;
import com.fly.agent.dao.entity.swe.SweRepoScaReportEntity;
import com.fly.agent.dao.mapper.swe.SweRepoBlacklistMapper;
import com.fly.agent.dao.mapper.swe.SweRepoScaReportMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SweRepoPrecheckServiceTest {

    @Test
    void rejectsBlacklistedRepository() {
        SweRepoBlacklistMapper blacklistMapper = mock(SweRepoBlacklistMapper.class);
        SweRepoScaReportMapper scaReportMapper = mock(SweRepoScaReportMapper.class);
        SweRepoBlacklistEntity entry = new SweRepoBlacklistEntity();
        entry.setRepo("acme/project");
        entry.setBenchmarks("swe-bench");
        when(blacklistMapper.selectOne(any())).thenReturn(entry);

        SweRepoPrecheckService service = new SweRepoPrecheckService(blacklistMapper, scaReportMapper);

        SweRepoPrecheckService.RepoPrecheckDecision decision = service.check("https://github.com/acme/project");

        assertFalse(decision.allowed());
        assertTrue(decision.reasonCode().contains("blacklisted"));
    }

    @Test
    void rejectsRejectedScaReport() {
        SweRepoBlacklistMapper blacklistMapper = mock(SweRepoBlacklistMapper.class);
        SweRepoScaReportMapper scaReportMapper = mock(SweRepoScaReportMapper.class);
        SweRepoScaReportEntity report = new SweRepoScaReportEntity();
        report.setCompatibilityStatus("REJECT");
        report.setLicenseSpdxId("GPL-3.0");
        when(blacklistMapper.selectOne(any())).thenReturn(null);
        when(scaReportMapper.selectOne(any())).thenReturn(report);

        SweRepoPrecheckService service = new SweRepoPrecheckService(blacklistMapper, scaReportMapper);

        SweRepoPrecheckService.RepoPrecheckDecision decision = service.check("acme/project");

        assertFalse(decision.allowed());
        assertTrue(decision.reasonCode().contains("sca"));
    }

    @Test
    void allowsRepositoryWithoutBlockingRecords() {
        SweRepoBlacklistMapper blacklistMapper = mock(SweRepoBlacklistMapper.class);
        SweRepoScaReportMapper scaReportMapper = mock(SweRepoScaReportMapper.class);
        when(blacklistMapper.selectOne(any())).thenReturn(null);
        when(scaReportMapper.selectOne(any())).thenReturn(null);

        SweRepoPrecheckService service = new SweRepoPrecheckService(blacklistMapper, scaReportMapper);

        assertTrue(service.check("acme/project").allowed());
    }
}
