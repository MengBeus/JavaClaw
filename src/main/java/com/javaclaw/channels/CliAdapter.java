package com.javaclaw.channels;

import com.javaclaw.shared.model.InboundMessage;
import com.javaclaw.shared.model.OutboundMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;

public class CliAdapter implements ChannelAdapter {

    private volatile boolean running;
    private Thread readThread;
    private Runnable onStop;

    public void onStop(Runnable callback) {
        this.onStop = callback;
    }

    @Override
    public String id() {
        return "cli";
    }

    @Override
    public void start(MessageSink sink) {
        running = true;
        readThread = Thread.startVirtualThread(() -> runLoop(sink));
    }

    private void runLoop(MessageSink sink) {
        var reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("JAVAClaw CLI (type /quit to exit)");
        while (running) {
            System.out.print("> ");
            try {
                var line = reader.readLine();
                if (line == null || "/quit".equals(line.trim())) {
                    stop();
                    if (onStop != null) onStop.run();
                    break;
                }
                if (line.isBlank()) continue;
                sink.accept(new InboundMessage("cli-user", "cli", line, Instant.now()));
            } catch (Exception e) {
                if (running) System.err.println("Read error: " + e.getMessage());
            }
        }
    }

    @Override
    public void send(OutboundMessage msg) {
        System.out.println(msg.content());
    }

    @Override
    public void stop() {
        running = false;
        if (readThread != null) readThread.interrupt();
    }
}
