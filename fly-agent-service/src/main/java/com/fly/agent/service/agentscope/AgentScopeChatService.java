package com.fly.agent.service.agentscope;

import com.fly.agent.common.util.JsonUtil;
import com.fly.agent.service.properties.ZhipuProperties;
import com.fly.agent.service.swe.SweRuntimeSettingsService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.memory.InMemoryMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AgentScope Chat 服务
 * 使用 ReActAgent 实现对话功能
 * 通过 OpenAIChatModel 调用智谱AI GLM-5（OpenAI 兼容模式）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScopeChatService {

    private static final String ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/coding/paas/v4";

    private final ZhipuProperties zhipuProperties;
    private final SweRuntimeSettingsService runtimeSettingsService;

    /**
     * 系统提示词
     */
    private static final String SYSTEM_PROMPT = """
            你是 Fly Agent 智能助手，一个基于 AgentScope Java 框架开发的 AI 智能体。

            你的职责：
            - 提供专业、友好的对话服务
            - 回答用户的问题
            - 协助用户完成各种任务

            请用简洁、准确的方式回复。
            """;

    /**
     * 创建 ReActAgent
     * 每个会话使用独立的 Agent 实例
     */
    private ReActAgent createAgent() {
        // 创建内存存储
        InMemoryMemory memory = new InMemoryMemory();

        // 构建 ReActAgent
        return ReActAgent.builder()
                .name("FlyAgent")
                .sysPrompt(SYSTEM_PROMPT)
                .model(createChatModel())
                .memory(memory)
                .build();
    }

    private OpenAIChatModel createChatModel() {
        GenerateOptions options = GenerateOptions.builder()
                .temperature(zhipuProperties.getTemperature())
                .maxTokens(zhipuProperties.getMaxTokens())
                .build();
        return OpenAIChatModel.builder()
                .apiKey(runtimeSettingsService.resolveZhipuApiKey(zhipuProperties.getApiKey()))
                .modelName(zhipuProperties.getModel())
                .baseUrl(ZHIPU_BASE_URL)
                .generateOptions(options)
                .stream(Boolean.TRUE)
                .build();
    }

    /**
     * 发送消息并获取完整响应
     *
     * @param userMessage 用户消息
     * @return AI 响应
     */
    public Mono<String> chat(String userMessage) {
        log.info("接收到用户消息: {}", userMessage);

        // 创建 Agent
        ReActAgent agent = createAgent();

        // 构建用户消息
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userMessage).build())
                .build();

        // 调用 Agent 并返回响应
        return agent.call(userMsg)
                .map(Msg::getTextContent)
                .doOnSuccess(response -> log.info("AI 响应成功, 长度: {}", response.length()));
    }

    /**
     * 发送消息并获取流式响应
     *
     * @param userMessage 用户消息
     * @return 流式响应（增量内容或JSON）
     */
    public Flux<String> chatStream(String userMessage) {
        log.info("接收到用户消息(流式): {}", userMessage);

        // 创建 Agent
        ReActAgent agent = createAgent();

        // 构建用户消息
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userMessage).build())
                .build();

        // 创建流式选项：使用累积模式
        StreamOptions streamOptions = StreamOptions.builder()
                .eventTypes(EventType.REASONING)    // 监听推理事件
                .incremental(false)                  // 累积模式
                .build();

        // 用于记录上次发送的内容，避免重复
        final String[] lastContent = {""};

        // 调用 Agent 的流式 API
        return agent.stream(userMsg, streamOptions)
                .filter(event -> {
                    Msg msg = event.getMessage();
                    String content = msg != null ? msg.getTextContent() : null;

                    // 过滤空内容
                    if (content == null || content.isEmpty()) {
                        return false;
                    }

                    // 对于非最后事件，过滤掉内容没有增长的事件
                    boolean isLast = event.isLast();
                    return isLast || content.length() > lastContent[0].length();
                })
                .map(event -> {
                    Msg msg = event.getMessage();
                    String content = msg.getTextContent();
                    boolean isLast = event.isLast();

                    if (isLast) {
                        // 最后一个事件：发送 JSON 格式的完整内容
                        // 使用 JSON 避免 SSE 换行符问题
                        String escapedContent = JsonUtil.escapeForJson(content);
                        return String.format("{\"isLast\":true,\"content\":\"%s\"}", escapedContent);
                    } else {
                        // 非最后事件：直接返回增量文本
                        String delta = content.substring(lastContent[0].length());
                        lastContent[0] = content;
                        return delta;
                    }
                })
                .doOnComplete(() -> log.info("流式响应完成"))
                .doOnError(error -> log.error("流式响应错误", error));
    }

    /**
     * 重置对话
     * 创建新的 Agent 实例以清空历史
     */
    public void reset() {
        log.info("重置对话");
        // 每次调用 createAgent() 都会创建新的实例，自带新的内存
    }
}
