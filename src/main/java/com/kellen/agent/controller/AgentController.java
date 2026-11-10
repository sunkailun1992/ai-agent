package com.kellen.agent.controller;

import com.kellen.agent.dto.AgentChatRequest;
import com.kellen.agent.dto.AgentModelResponse;
import com.kellen.agent.service.AiChatClientProvider;
import com.kellen.agent.service.PatientAnalysisAgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Agent 执行层 HTTP 接口。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AiChatClientProvider aiChatClientProvider;
    private final PatientAnalysisAgentService patientAnalysisAgentService;

    public AgentController(AiChatClientProvider aiChatClientProvider,
                           PatientAnalysisAgentService patientAnalysisAgentService) {
        this.aiChatClientProvider = aiChatClientProvider;
        this.patientAnalysisAgentService = patientAnalysisAgentService;
    }

    /**
     * 查询 Agent 执行层默认模型。
     *
     * @return 默认模型信息。
     */
    @GetMapping(value = "/model", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentModelResponse model(@RequestParam(value = "provider", required = false) String provider) {
        AiChatClientProvider.CurrentClient currentClient = aiChatClientProvider.selection(provider);
        return new AgentModelResponse(currentClient.provider(), currentClient.model(), aiChatClientProvider.supportedProviders());
    }

    /**
     * 患者分析 Agent 流式接口。
     *
     * @param request 调用请求。
     * @param protocol 调用方要求的内部 SSE 协议，可空以兼容只消费 data 的旧调用方。
     * @return 带明确错误事件的 SSE 文本流，异常正文仅使用固定友好文案。
     */
    @PostMapping(value = "/patient-analysis/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> patientAnalysis(@RequestBody AgentChatRequest request,
            @RequestHeader(value = "X-AI-Stream-Protocol", required = false) String protocol) {
        if (protocol != null && !"typed-sse-v1".equals(protocol)) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "AI_AGENT_STREAM_PROTOCOL_UNSUPPORTED");
        }
        // 在 Servlet 入口捕获服务流的可信身份与父 trace，不能延迟到订阅线程再读请求上下文。
        Flux<ServerSentEvent<String>> events = patientAnalysisAgentService.stream(request)
                .map(text -> ServerSentEvent.builder(text).build())
                .onErrorResume(error -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error").data("\n[AI Agent 调用失败] 请稍后重试").build()));
        return ResponseEntity.ok().header("X-AI-Stream-Protocol", "typed-sse-v1").body(events);
    }
}
