package com.javaclaw.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class DockerExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutor.class);

    @Override
    public String execute(String command, String workDir, long timeoutSeconds) {
        try {
            var pb = new ProcessBuilder("docker", "run", "--rm",
                    "--memory=256m", "--cpus=0.5", "--pids-limit=64",
                    "--network=none",
                    "-v", workDir + ":/work", "-w", "/work",
                    "ubuntu:22.04", "bash", "-c", command);
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var stdout = new java.io.ByteArrayOutputStream();
            var reader = Thread.startVirtualThread(() -> {
                try { proc.getInputStream().transferTo(stdout); } catch (Exception ignored) {}
            });
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                reader.join(5000);
                return "[TIMEOUT] Command exceeded " + timeoutSeconds + "s";
            }
            reader.join(5000);
            return stdout.toString();
        } catch (Exception e) {
            throw new RuntimeException("Docker execution failed", e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            var proc = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true).start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            log.debug("Docker not available: {}", e.getMessage());
            return false;
        }
    }
}
