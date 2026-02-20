package com.javaclaw.channels;

import com.javaclaw.shared.model.OutboundMessage;

public interface ChannelAdapter {
    String id();
    void start(MessageSink sink);
    void send(OutboundMessage msg);
    void stop();
}
