package com.javaclaw.channels;

import com.javaclaw.shared.model.InboundMessage;

@FunctionalInterface
public interface MessageSink {
    void accept(InboundMessage message);
}
