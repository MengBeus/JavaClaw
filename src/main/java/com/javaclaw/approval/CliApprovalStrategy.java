package com.javaclaw.approval;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

public class CliApprovalStrategy implements ApprovalStrategy {

    private final BufferedReader reader;
    private final PrintStream out;

    public CliApprovalStrategy() {
        this(new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    public CliApprovalStrategy(BufferedReader reader, PrintStream out) {
        this.reader = reader;
        this.out = out;
    }

    @Override
    public boolean approve(String toolName, String arguments, String channelId, String senderId) {
        out.printf("[APPROVAL] Tool '%s' requires confirmation.%n", toolName);
        out.printf("  Arguments: %s%n", arguments);
        out.print("  Allow? (y/n): ");
        out.flush();
        try {
            var line = reader.readLine();
            return line != null && line.trim().equalsIgnoreCase("y");
        } catch (Exception e) {
            return false;
        }
    }
}
