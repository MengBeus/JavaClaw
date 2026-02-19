package com.javaclaw.shared.model;

import java.time.Instant;

public record InboundMessage(
    String senderId,
    String channelId,
    String content,
    Instant timestamp
) {}
