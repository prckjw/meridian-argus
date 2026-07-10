package com.prckjw.meridianargus.demo;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.provider.openai.OpenAICallWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hit GET /ask?prompt=hello, then check GET /actuator/prometheus for
 * meridian_argus.* meters. No real OpenAI API key needed — the SDK call is
 * simulated by {@link #simulateOpenAICall}; see {@link FakeChatCompletionResponse}
 * for how to swap in the real SDK.
 */
@RestController
public class DemoController {

    private final OpenAICallWrapper wrapper;

    public DemoController(LLMObservabilityRegistry registry) {
        this.wrapper = new OpenAICallWrapper(registry, "gpt-4o", "meridian-argus-demo");
    }

    @GetMapping("/ask")
    public String ask(@RequestParam(defaultValue = "Tell me something interesting.") String prompt) {
        FakeChatCompletionResponse response = wrapper.call(
                () -> simulateOpenAICall(prompt),
                r -> TokenUsage.of(r.promptTokens(), r.completionTokens())
        );
        return response.content();
    }

    /**
     * Replace this with a real call, e.g.:
     * {@code openAiClient.chat().completions().create(request)}
     */
    private FakeChatCompletionResponse simulateOpenAICall(String prompt) {
        int promptTokens = Math.max(1, prompt.length() / 4);
        int completionTokens = ThreadLocalRandom.current().nextInt(50, 200);
        return new FakeChatCompletionResponse(
                "This is a simulated response to: " + prompt,
                promptTokens,
                completionTokens
        );
    }
}
