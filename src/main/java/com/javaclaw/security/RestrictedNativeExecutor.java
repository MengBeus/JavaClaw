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
    public String execute(String command, String workDir, long timeoutSeconds) {
        if (workDir != null && !isAllowedDir(workDir)) {
            return "[BLOCKED] Working directory not in whitelist: " + workDir;
        }
        for (var blocked : BLOCKED) {
            if (command.contains(blocked)) {
                return "[BLOCKED] Command contains dangerous pattern: " + blocked;
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
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                return "[TIMEOUT] Command exceeded " + timeoutSeconds + "s";
            }
            return new String(proc.getInputStream().readAllBytes());
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
