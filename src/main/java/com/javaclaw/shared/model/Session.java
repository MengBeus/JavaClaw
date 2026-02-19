package com.javaclaw.shared.model;

import java.time.Instant;

public record Session(
    String id,
    String userId,
    String channelId,
    Instant createdAt
) {}
