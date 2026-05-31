package com.fly.agent.task.job;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.swe.SwePipelineRunDTO;
import com.fly.agent.common.dto.swe.SwePipelineStartRequest;
import com.fly.agent.service.swe.SwePipelineService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * XXL-Job entry for SWE-Pro pipeline execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwePipelineJob {

    private final SwePipelineService swePipelineService;

    /**
     * Starts a SWE-Pro pipeline.
     *
     * <p>Job param accepts either a task id such as {@code 1}, or JSON such as
     * {@code {"taskId":1,"samplePath":"/tmp/package","workspacePath":"/tmp/work"}}.</p>
     */
    @XxlJob("sweProPipelineJob")
    public void sweProPipelineJob() {
        try {
            SwePipelineStartRequest request = parseRequest(XxlJobHelper.getJobParam());
            SwePipelineRunDTO run = swePipelineService.startRun(request);
            String message = "SWE-Pro pipeline started, runId=" + run.getId();
            log.info(message);
            XxlJobHelper.handleSuccess(message);
        } catch (Exception e) {
            log.error("SWE-Pro pipeline job failed", e);
            XxlJobHelper.handleFail("SWE-Pro pipeline job failed: " + e.getMessage());
        }
    }

    private SwePipelineStartRequest parseRequest(String param) {
        if (!StringUtils.hasText(param)) {
            throw new IllegalArgumentException("job param is required");
        }
        String trimmed = param.trim();
        if (!trimmed.startsWith("{")) {
            SwePipelineStartRequest request = new SwePipelineStartRequest();
            request.setTaskId(Long.parseLong(trimmed));
            return request;
        }

        JSONObject json = JSON.parseObject(trimmed);
        SwePipelineStartRequest request = new SwePipelineStartRequest();
        request.setTaskId(json.getLong("taskId"));
        request.setResumeRunId(json.getLong("resumeRunId"));
        request.setResumeFromStage(json.getString("resumeFromStage"));
        request.setForceResume(json.getBoolean("forceResume"));
        request.setSamplePath(json.getString("samplePath"));
        request.setWorkspacePath(json.getString("workspacePath"));
        return request;
    }
}
