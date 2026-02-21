package com.javaclaw.security;

import com.javaclaw.shared.config.SandboxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DockerExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutor.class);
    private final SandboxConfig config;

    public DockerExecutor() { this(SandboxConfig.defaults()); }

    public DockerExecutor(SandboxConfig config) { this.config = config; }

    @Override
    public ExecutionResult execute(String command, String workDir, long timeoutSeconds, String toolName) {
        return execute(command, workDir, timeoutSeconds, toolName, null);
    }

    @Override
    public ExecutionResult execute(String command, String workDir, long timeoutSeconds, String toolName, Map<String, String> env) {
        try {
            var cmd = new ArrayList<String>();
            cmd.add("docker"); cmd.add("run"); cmd.add("--rm");
            cmd.add("--memory=" + config.memory());
            cmd.add("--cpus=" + config.cpus());
            cmd.add("--pids-limit=" + config.pidsLimit());
            if (!config.networkWhitelist().contains(toolName)) {
                cmd.add("--network=none");
            }
            if (env != null) {
                for (var entry : env.entrySet()) {
                    cmd.add("-e");
                    cmd.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            cmd.add("-v"); cmd.add(workDir + ":/work");
            cmd.add("-w"); cmd.add("/work");
            cmd.add("ubuntu:22.04"); cmd.add("bash"); cmd.add("-c"); cmd.add(command);

            var pb = new ProcessBuilder(cmd);
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
            throw new RuntimeException("Docker execution failed", e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            var proc = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true).start();
            var drain = Thread.startVirtualThread(() -> {
                try { proc.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) {}
            });
            var done = proc.waitFor(5, TimeUnit.SECONDS);
            if (!done) proc.destroyForcibly();
            drain.join(2000);
            return done && proc.exitValue() == 0;
        } catch (Exception e) {
            log.debug("Docker not available: {}", e.getMessage());
            return false;
        }
    }
}
