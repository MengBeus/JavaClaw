package com.javaclaw.security;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RestrictedNativeExecutor implements ToolExecutor {

    private static final Set<String> BLOCKED = Set.of(
            "rm -rf /", "mkfs", "dd if=", ":(){ :|:&", "shutdown", "reboot");

    private final Set<String> allowedDirs;

    public RestrictedNativeExecutor(Set<String> allowedDirs) {
        this.allowedDirs = allowedDirs;
    }

    @Override
    public ExecutionResult execute(String command, String workDir, long timeoutSeconds, String toolName) {
        if (workDir != null && !isAllowedDir(workDir)) {
            return new ExecutionResult("[BLOCKED] Working directory not in whitelist: " + workDir, -1);
        }
        for (var blocked : BLOCKED) {
            if (command.contains(blocked)) {
                return new ExecutionResult("[BLOCKED] Command contains dangerous pattern: " + blocked, -1);
            }
        }
        try {
            var shell = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{"cmd", "/c", command}
                    : new String[]{"bash", "-c", command};
            var pb = new ProcessBuilder(shell);
            if (workDir != null) pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var stdout = new java.io.ByteArrayOutputStream();
            var reader = Thread.startVirtualThread(() -> {
                try { proc.getInputStream().transferTo(stdout); } catch (Exception ignored) {}
            });
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                reader.join(5000);
                return new ExecutionResult("[TIMEOUT] Command exceeded " + timeoutSeconds + "s", -1);
            }
            reader.join(5000);
            return new ExecutionResult(stdout.toString(), proc.exitValue());
        } catch (Exception e) {
            throw new RuntimeException("Native execution failed", e);
        }
    }

    private boolean isAllowedDir(String dir) {
        if (allowedDirs.isEmpty()) return true;
        var normalized = Path.of(dir).toAbsolutePath().normalize();
        return allowedDirs.stream()
                .anyMatch(a -> normalized.startsWith(Path.of(a).toAbsolutePath().normalize()));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
