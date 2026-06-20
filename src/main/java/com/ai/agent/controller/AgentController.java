package com.ai.agent.controller;

import com.ai.agent.dto.AgentChatRequest;
import com.ai.agent.dto.AgentModelResponse;
import com.ai.agent.dto.QuestionnaireInterpretRequest;
import com.ai.agent.dto.QuestionnaireInterpretResponse;
import com.ai.agent.dto.QuestionnaireReplyRequest;
import com.ai.agent.dto.QuestionnaireReplyResponse;
import com.ai.agent.service.AiChatClientProvider;
import com.ai.agent.service.PatientAnalysisAgentService;
import com.ai.agent.service.QuestionnaireAgentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Agent 执行层 HTTP 接口。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AiChatClientProvider aiChatClientProvider;
    private final PatientAnalysisAgentService patientAnalysisAgentService;
    private final QuestionnaireAgentService questionnaireAgentService;

    public AgentController(AiChatClientProvider aiChatClientProvider,
                           PatientAnalysisAgentService patientAnalysisAgentService,
                           QuestionnaireAgentService questionnaireAgentService) {
        this.aiChatClientProvider = aiChatClientProvider;
        this.patientAnalysisAgentService = patientAnalysisAgentService;
        this.questionnaireAgentService = questionnaireAgentService;
    }

    /**
     * 查询 Agent 执行层默认模型。
     *
     * @return 默认模型信息。
     */
    @GetMapping(value = "/model", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentModelResponse model() {
        AiChatClientProvider.CurrentClient currentClient = aiChatClientProvider.defaultSelection();
        return new AgentModelResponse(currentClient.provider(), currentClient.model());
    }

    /**
     * 患者分析 Agent 流式接口。
     *
     * @param request 调用请求。
     * @return SSE 文本流。
     */
    @PostMapping(value = "/patient-analysis/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> patientAnalysis(@RequestBody AgentChatRequest request) {
        return patientAnalysisAgentService.stream(request);
    }

    /**
     * 问卷自然提问 Agent。
     *
     * @param request 调用请求。
     * @return 自然提问响应。
     */
    @PostMapping(value = "/questionnaire/reply", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionnaireReplyResponse questionnaireReply(@RequestBody QuestionnaireReplyRequest request) {
        return questionnaireAgentService.reply(request);
    }

    /**
     * 问卷答案解释 Agent。
     *
     * @param request 调用请求。
     * @return 结构化答案解释。
     */
    @PostMapping(value = "/questionnaire/interpret", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionnaireInterpretResponse questionnaireInterpret(@RequestBody QuestionnaireInterpretRequest request) {
        return questionnaireAgentService.interpret(request);
    }
}
