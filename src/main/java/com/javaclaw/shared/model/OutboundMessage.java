package com.javaclaw.shared.model;

import java.util.Map;

public record OutboundMessage(
    String channelId,
    String content,
    Map<String, String> metadata
)
