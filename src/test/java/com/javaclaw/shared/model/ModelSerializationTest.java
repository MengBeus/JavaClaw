package com.javaclaw.shared.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void inboundMessageRoundTrip() throws Exception {
        InboundMessage message = new InboundMessage(
            "user-1",
            "channel-1",
            "hello",
            Instant.parse("2026-02-20T00:00:00Z")
        );

        assertRoundTrip(message, InboundMessage.class);
    }

    @Test
    void outboundMessageRoundTrip() throws Exception {
        OutboundMessage message = new OutboundMessage(
            "channel-1",
            "reply",
            Map.of("source", "test")
        );

        assertRoundTrip(message, OutboundMessage.class);
    }

    @Test
    void agentRequestRoundTrip() throws Exception {
        AgentRequest request = new AgentRequest(
            "session-1",
            "how are you",
            Map.of("lang", "zh", "turn", 3)
        );

        assertRoundTrip(request, AgentRequest.class);
    }

    @Test
    void agentResponseRoundTrip() throws Exception {
        AgentResponse response = new AgentResponse(
            "I am fine",
            List.of(Map.of("name", "shell", "id", "tool-1")),
            Map.of("promptTokens", 10, "completionTokens", 5)
        );

        assertRoundTrip(response, AgentResponse.class);
    }

    @Test
    void sessionRoundTrip() throws Exception {
        Session session = new Session(
            "session-1",
            "user-1",
            "channel-1",
            Instant.parse("2026-02-20T00:00:00Z")
        );

        assertRoundTrip(session, Session.class);
    }

    @Test
    void chatMessageRoundTrip() throws Exception {
        ChatMessage message = new ChatMessage("assistant", "done", "tool-call-1");

        assertRoundTrip(message, ChatMessage.class);
    }

    private <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        String json = objectMapper.writeValueAsString(value);
        T restored = objectMapper.readValue(json, type);
        assertThat(restored).isEqualTo(value);
    }
}
