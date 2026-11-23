package com.seatlock.assistant.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Adapter from {@link LlmClient} to Spring AI's {@link ChatClient} over the auto-configured Gemini
 * {@link ChatModel} (model + temperature from {@code spring.ai.google.genai.chat.*}). No retries here or in the
 * gateway: a retry would spend free-tier quota twice for one user click.
 */
public class GeminiLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final String model;

    public GeminiLlmClient(ChatModel chatModel, String model) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.model = model;
    }

    @Override
    public LlmReply complete(String systemPrompt, String userText) {
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new LlmReply(null, null, null);
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new LlmReply(response.getResult().getOutput().getText(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String model() {
        return model;
    }
}
