package com.javaclaw.channels;

import com.javaclaw.shared.model.InboundMessage;
import com.javaclaw.shared.model.OutboundMessage;

import java.io.BufferedReader;
import java.time.Instant;

public class CliAdapter implements ChannelAdapter {

    private final BufferedReader reader;
    private volatile boolean running;
    private Thread readThread;
    private Runnable onStop;

    public CliAdapter(BufferedReader reader) {
        this.reader = reader;
    }

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
        System.out.println("JAVAClaw CLI (type /quit or /exit to stop CLI)");
        while (running) {
            System.out.print("> ");
            String line;
            try {
                line = reader.readLine();
            } catch (Exception e) {
                if (!running) break;
                var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                System.err.println("Input read error: " + msg);
                continue;
            }

            if (line == null) {
                stop();
                if (onStop != null) onStop.run();
                break;
            }
            var input = line.trim();
            if (input.isEmpty()) continue;
            if ("/quit".equals(input) || "/exit".equals(input)) {
                stop();
                if (onStop != null) onStop.run();
                break;
            }

            try {
                sink.accept(new InboundMessage("cli-user", "cli", input, Instant.now()));
            } catch (Exception e) {
                var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                System.err.println("Message handling error: " + msg);
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
