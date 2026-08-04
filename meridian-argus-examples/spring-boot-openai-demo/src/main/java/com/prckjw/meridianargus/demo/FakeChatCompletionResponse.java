package com.prckjw.meridianargus.demo;

/**
 * Stand-in for {@code com.openai.models.chat.completions.ChatCompletion} so
 * this demo runs end-to-end without requiring a real API key or a pinned
 * OpenAI SDK version. Swap {@link DemoController#ask} for a real SDK
 * call and replace this with the actual response type — the
 * {@code OpenAICallWrapper} usage pattern stays identical either way.
 */
public record FakeChatCompletionResponse(String content, int promptTokens, int completionTokens) {
}
