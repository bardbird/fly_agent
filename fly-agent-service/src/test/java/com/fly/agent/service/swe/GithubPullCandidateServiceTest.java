package com.fly.agent.service.swe;

import com.fly.agent.common.dto.swe.GithubPullCandidateDTO;
import com.fly.agent.common.dto.swe.GithubPullScanResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubPullCandidateServiceTest {

    @Test
    void resolvedIssueReferencesAreExtractedFromTitleAndBody() {
        List<Integer> numbers = GithubPullCandidateService.extractResolvedIssueNumbers(
                "Fix parser crash",
                "This closes #42 and resolves owner/repo#77. Related to #99.");

        assertEquals(List.of(42, 77), numbers);
    }

    @Test
    void candidatesExposeIssueAndOracleMetadata() {
        GithubPullCandidateDTO candidate = new GithubPullCandidateDTO();
        candidate.setIssueUrl("https://github.com/acme/project/issues/42");
        candidate.setIssueNumbers("[42]");
        candidate.setProblemStatement("Parser crashes on empty input");
        candidate.setHintsText("Maintainer pointed at tokenizer.");
        candidate.setTestPatchPresent(true);
        candidate.setFailToPass("[\"tests/test_parser.py::test_empty\"]");
        candidate.setPassToPass("[]");
        candidate.setBenchmarkStatus("UNKNOWN");
        candidate.setFailedHistoryStatus("UNKNOWN");

        assertEquals("https://github.com/acme/project/issues/42", candidate.getIssueUrl());
        assertEquals("[42]", candidate.getIssueNumbers());
        assertTrue(candidate.getTestPatchPresent());
        assertFalse(candidate.getProblemStatement().isBlank());
        assertEquals("UNKNOWN", candidate.getBenchmarkStatus());
        assertEquals("UNKNOWN", candidate.getFailedHistoryStatus());
    }

    @Test
    void scanResponseCountsPullsSkippedForMissingResolvedIssue() {
        GithubPullScanResponse response = new GithubPullScanResponse();
        response.setSkippedNoResolvedIssue(3);

        assertEquals(3, response.getSkippedNoResolvedIssue());
    }

    @Test
    void parsesGithubIssueUrlForDirectCandidateLookup() {
        GithubPullCandidateService.IssueRef ref = GithubPullCandidateService.parseIssueUrl(
                "https://github.com/truelockmc/streambert/issues/23");

        assertEquals("truelockmc/streambert", ref.repo());
        assertEquals(23, ref.number());
    }

    @Test
    void detectsPullClosingSpecificIssue() {
        assertTrue(GithubPullCandidateService.closesIssue(
                "New grid view",
                "Closes #23 and mentions #99",
                23));
        assertFalse(GithubPullCandidateService.closesIssue(
                "New grid view",
                "Related to #23",
                23));
    }

    @Test
    void mergesIssueNumbersForIdempotentCandidateUpsert() throws Exception {
        GithubPullCandidateService service = new GithubPullCandidateService("", null, null, null);
        Method method = GithubPullCandidateService.class.getDeclaredMethod(
                "mergeJsonIntArrays",
                String.class,
                String.class);
        method.setAccessible(true);

        String merged = (String) method.invoke(service, "[4809]", "[4704,4809]");

        assertEquals("[4809,4704]", merged);
    }

    @Test
    void mergesDistinctIssueEvidenceWithoutDuplicatingText() throws Exception {
        GithubPullCandidateService service = new GithubPullCandidateService("", null, null, null);
        Method method = GithubPullCandidateService.class.getDeclaredMethod(
                "mergeEvidenceText",
                String.class,
                String.class);
        method.setAccessible(true);

        String merged = (String) method.invoke(service, "issue 4809", "issue 4704");
        String repeated = (String) method.invoke(service, merged, "issue 4704");

        assertEquals("issue 4809\n\n---\n\nissue 4704", merged);
        assertEquals(merged, repeated);
    }
}
