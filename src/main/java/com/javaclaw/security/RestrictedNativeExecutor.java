package com.javaclaw.security;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RestrictedNativeExecutor implements ToolExecutor {

    private static final Set<String> BLOCKED = Set.of(
            "rm -rf /", "mkfs", "dd if=", ":(){ :|:&", "shutdown", "reboot");

    @Override
    public String execute(String command, String workDir, long timeoutSeconds) {
        for (var blocked : BLOCKED) {
            if (command.contains(blocked)) {
                return "[BLOCKED] Command contains dangerous pattern: " + blocked;
            }
        }
        try {
            var pb = new ProcessBuilder("bash", "-c", command);
            if (workDir != null) pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes());
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "[TIMEOUT] Command exceeded " + timeoutSeconds + "s";
            }
            return output;
        } catch (Exception e) {
            throw new RuntimeException("Native execution failed", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
