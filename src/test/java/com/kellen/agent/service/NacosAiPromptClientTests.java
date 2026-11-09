package com.kellen.agent.service;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import com.kellen.agent.config.NacosAiPromptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prompt 读取客户端坏掉后的自愈：退避、重建 SDK 客户端、空结果重查，以及重建期间不丢已读到的 Prompt。
 *
 * <p>SDK 客户端首次读取失败或返回空订阅后，应在同一进程中恢复。这里用假的 {@link AiService} 复现「坏掉的客户端」。</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class NacosAiPromptClientTests {

    private static final String SUMMARY = "summary-fixture";
    private static final String MEAL = "media-fixture";
    private static final long RETRY = NacosAiPromptClient.RETRY_INTERVAL_MILLIS;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Deque<AiService> nextServices = new ArrayDeque<>();
    private final List<AiService> created = new ArrayList<>();
    private NacosAiPromptProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NacosAiPromptProperties();
        properties.setEnabled(true);
        properties.setServerAddr("127.0.0.1:8848");
        properties.setNamespace("test");
        properties.setLabel("latest");
    }

    @Test
    void resolvedVersionAndTemplateComeFromTheSameSubscriptionSnapshot() throws Exception {
        AiService sdk = mock(AiService.class);
        when(sdk.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "SYNTHETIC_PRIVATE_PROMPT"));
        NacosAiPromptClient client = client(sdk);
        var snapshot = client.snapshot(SUMMARY).orElseThrow();
        assertThat(snapshot.version()).isEqualTo("1.0.7");
        assertThat(snapshot.template()).isEqualTo("SYNTHETIC_PRIVATE_PROMPT");
        assertThat(snapshot.source()).isEqualTo("nacos");
        assertThat(snapshot.toString()).doesNotContain("SYNTHETIC_PRIVATE_PROMPT");
        assertThat(client.prompt(SUMMARY)).contains(snapshot.template());
        verify(sdk, times(1)).subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any());
    }

    @Test
    void namespaceChangeCannotReturnThePreviousNamespacesCachedTemplate() throws Exception {
        AiService old = mock(AiService.class);
        when(old.subscribePrompt(any(), any(), any(), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.0", "old-namespace-private-template"));
        AiService replacement = mock(AiService.class);
        when(replacement.subscribePrompt(any(), any(), any(), any())).thenReturn(null);
        NacosAiPromptClient client = client(old, replacement);
        assertThat(client.prompt(SUMMARY)).contains("old-namespace-private-template");
        properties.setNamespace("github");
        assertThat(client.prompt(SUMMARY)).isEmpty();
        verify(old).shutdown();
        assertThat(created).hasSize(2);
    }

    @Test
    void servesTheSubscribedTemplateWithoutAskingNacosAgain(CapturedOutput output) throws Exception {
        AiService sdk = mock(AiService.class);
        when(sdk.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "通用测试提示词正文"));
        NacosAiPromptClient client = client(sdk);

        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");

        verify(sdk, times(1)).subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any());
        assertThat(created).hasSize(1);
        assertThat(output).doesNotContain("通用测试提示词正文");
    }

    @Test
    void rebuildsTheSdkClientAfterAFailureOnceTheRetryIntervalHasPassed(CapturedOutput output) throws Exception {
        AiService broken = mock(AiService.class);
        when(broken.subscribePrompt(any(), any(), any(), any()))
                .thenThrow(new NacosException(NacosException.SERVER_ERROR, "Client not connected"));
        AiService healthy = mock(AiService.class);
        when(healthy.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "通用测试提示词正文"));
        NacosAiPromptClient client = client(broken, healthy);

        assertThat(client.prompt(SUMMARY)).isEmpty();
        assertThat(output).contains("promptKey=" + SUMMARY).contains("errCode=500").contains("type=NacosException");

        // 退避期内既不重建，也不再打扰 Nacos：故障期间每次请求都去连一遍只会更糟。
        now.addAndGet(RETRY - 1);
        assertThat(client.prompt(SUMMARY)).isEmpty();
        verify(broken, times(1)).subscribePrompt(any(), any(), any(), any());
        assertThat(created).hasSize(1);

        now.addAndGet(1);
        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        assertThat(created).hasSize(2);
        verify(broken).shutdown();
    }

    @Test
    void anEmptySubscriptionIsQueriedAgainAfterTheRetryInterval() throws Exception {
        AiService sdk = mock(AiService.class);
        // SDK 遇到 NOT_FOUND 时返回 null 并缓存空订阅，若之后监听器一直没来，旧代码会永远读不到。
        when(sdk.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(null)
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "通用测试提示词正文"));
        NacosAiPromptClient client = client(sdk);

        assertThat(client.prompt(SUMMARY)).isEmpty();
        assertThat(client.prompt(SUMMARY)).isEmpty();
        verify(sdk, times(1)).subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any());

        now.addAndGet(RETRY);
        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        verify(sdk).unsubscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any());
        verify(sdk, times(2)).subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any());
        assertThat(created).hasSize(1);
    }

    @Test
    void aPromptStillMissingAfterARequeryRebuildsTheClientNextTime() throws Exception {
        AiService stale = mock(AiService.class);
        when(stale.subscribePrompt(any(), any(), any(), any())).thenReturn(null);
        AiService fresh = mock(AiService.class);
        when(fresh.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "通用测试提示词正文"));
        NacosAiPromptClient client = client(stale, fresh);

        assertThat(client.prompt(SUMMARY)).isEmpty();
        now.addAndGet(RETRY);
        assertThat(client.prompt(SUMMARY)).isEmpty();
        assertThat(created).hasSize(1);

        now.addAndGet(RETRY);
        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        assertThat(created).hasSize(2);
        verify(stale).shutdown();
    }

    @Test
    void keepsServingATemplateAlreadyReadWhileTheRebuiltClientCannotReachNacos() throws Exception {
        AiService first = mock(AiService.class);
        when(first.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "通用测试提示词正文"));
        when(first.subscribePrompt(eq(MEAL), isNull(), eq("latest"), any()))
                .thenThrow(new NacosException(NacosException.SERVER_ERROR, "boom"));
        AiService second = mock(AiService.class);
        when(second.subscribePrompt(any(), any(), any(), any()))
                .thenThrow(new NacosException(NacosException.CLIENT_DISCONNECT, "down"));
        NacosAiPromptClient client = client(first, second);

        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        assertThat(client.prompt(MEAL)).isEmpty();

        now.addAndGet(RETRY);
        assertThat(client.prompt(MEAL)).isEmpty();
        assertThat(created).hasSize(2);

        // 一个 key 出错重建了客户端，已经读到的其他 Prompt 不能跟着变成读不到；退避期内也不去新客户端上重订。
        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        verify(second, never()).subscribePrompt(eq(SUMMARY), any(), any(), any());
    }

    @Test
    void keepsServingATemplateAlreadyReadWhenTheClientCannotEvenBeRebuilt() throws Exception {
        AiService first = mock(AiService.class);
        when(first.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "通用测试提示词正文"));
        when(first.subscribePrompt(eq(MEAL), isNull(), eq("latest"), any()))
                .thenThrow(new NacosException(NacosException.SERVER_ERROR, "boom"));
        // 只给一个假客户端：重建时工厂本身就抛错（Nacos 不可达时 SDK 建客户端也会失败）。
        NacosAiPromptClient client = client(first);

        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
        assertThat(client.prompt(MEAL)).isEmpty();

        now.addAndGet(RETRY);
        assertThat(client.prompt(MEAL)).isEmpty();
        now.addAndGet(RETRY);
        assertThat(client.prompt(MEAL)).isEmpty();

        assertThat(client.prompt(SUMMARY)).contains("通用测试提示词正文");
    }

    @Test
    void templatesReadBeforeARebuildAreSubscribedAgainOnTheNewClient() throws Exception {
        AiService first = mock(AiService.class);
        when(first.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.6", "旧版摘要提示词"));
        when(first.subscribePrompt(eq(MEAL), isNull(), eq("latest"), any()))
                .thenThrow(new NacosException(NacosException.SERVER_ERROR, "boom"));
        AiService second = mock(AiService.class);
        when(second.subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(SUMMARY, "1.0.7", "新版摘要提示词"));
        when(second.subscribePrompt(eq(MEAL), isNull(), eq("latest"), any()))
                .thenReturn(new Prompt(MEAL, "1.2.3", "配图提示词"));
        NacosAiPromptClient client = client(first, second);

        assertThat(client.prompt(SUMMARY)).contains("旧版摘要提示词");
        assertThat(client.prompt(MEAL)).isEmpty();

        now.addAndGet(RETRY);
        assertThat(client.prompt(MEAL)).contains("配图提示词");
        // 旧客户端已关掉，它上面的订阅收不到更新了：下次读时改到新客户端上订阅，Nacos 里的新版本才能生效。
        assertThat(client.prompt(SUMMARY)).contains("新版摘要提示词");
        verify(second).subscribePrompt(eq(SUMMARY), isNull(), eq("latest"), any());
    }

    @Test
    void readsNothingWhenTheRegistryIsDisabled() {
        properties.setEnabled(false);
        NacosAiPromptClient client = client();

        assertThat(client.prompt(SUMMARY)).isEmpty();
        assertThat(created).isEmpty();
    }

    private NacosAiPromptClient client(AiService... services) {
        nextServices.addAll(List.of(services));
        return new NacosAiPromptClient(properties, nacosProperties -> {
            AiService next = nextServices.poll();
            if (next == null) {
                throw new NacosException(NacosException.SERVER_ERROR, "no more fake clients");
            }
            created.add(next);
            return next;
        }, now::get);
    }
}
