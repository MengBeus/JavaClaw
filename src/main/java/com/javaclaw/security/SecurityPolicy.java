package com.javaclaw.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SecurityPolicy {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    private static final Set<String> UNIX_ENV = Set.of(
            "PATH", "HOME", "TERM", "LANG", "USER", "SHELL", "TMPDIR");
    private static final Set<String> WIN_ENV = Set.of(
            "PATH", "SystemRoot", "ComSpec", "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH");

    private static final Set<String> HIGH_RISK = Set.of(
            "rm", "mkfs", "dd", "shutdown", "reboot", "sudo", "su",
            "chown", "chmod", "useradd", "userdel", "passwd",
            "mount", "umount", "iptables", "ufw", "nc", "telnet");
    private static final Set<String> MEDIUM_RISK = Set.of(
            "git", "npm", "yarn", "cargo", "pip",
            "touch", "mkdir", "mv", "cp", "ln");

    private final ActionTracker actionTracker;
    private final Path workspaceRoot;
    private final boolean workspaceOnly;
    private final Set<String> allowedDomains;

    public SecurityPolicy(Path workspaceRoot, boolean workspaceOnly,
                          int maxActionsPerHour, Set<String> allowedDomains) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.workspaceOnly = workspaceOnly;
        this.actionTracker = new ActionTracker(maxActionsPerHour);
        this.allowedDomains = allowedDomains;
    }

    public void checkRateLimit(String toolName) {
        actionTracker.track(toolName);
    }

    // --- Path Sandboxing (5-layer) ---

    public Path validatePath(String rawPath, boolean checkSize) throws SecurityException {
        if (rawPath == null || rawPath.contains("\0")) {
            throw new SecurityException("Invalid path: null bytes");
        }
        // Layer 1: policy check — reject traversal
        var p = Path.of(rawPath);
        for (var component : p) {
            if ("..".equals(component.toString())) {
                throw new SecurityException("Path traversal not allowed");
            }
        }
        var resolved = workspaceRoot.resolve(p).normalize();
        if (workspaceOnly && !resolved.startsWith(workspaceRoot)) {
            throw new SecurityException("Path escapes workspace");
        }

        // Layer 2: canonicalize (resolve symlinks)
        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException e) {
            // File doesn't exist yet — check parent
            var parent = resolved.getParent();
            if (parent == null) throw new SecurityException("Invalid path");
            try {
                var realParent = parent.toRealPath();
                if (!realParent.startsWith(workspaceRoot.toRealPath())) {
                    throw new SecurityException("Path escapes workspace");
                }
            } catch (IOException ex) {
                throw new SecurityException("Parent directory does not exist");
            }
            return resolved;
        }

        // Layer 3: escape check after canonicalization
        try {
            if (!real.startsWith(workspaceRoot.toRealPath())) {
                throw new SecurityException("Resolved path escapes workspace");
            }
        } catch (IOException e) {
            throw new SecurityException("Cannot resolve workspace root");
        }

        // Layer 4: symlink check
        if (Files.isSymbolicLink(resolved)) {
            throw new SecurityException("Symlinks not allowed");
        }

        // Layer 5: size check
        if (checkSize && Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.size(real) > MAX_FILE_SIZE_BYTES) {
                    throw new SecurityException("File too large: max " + MAX_FILE_SIZE_BYTES + " bytes");
                }
            } catch (IOException e) {
                throw new SecurityException("Cannot read file size");
            }
        }

        return real;
    }

    // --- Domain Validation (SSRF defense) ---

    public void validateDomain(String url) throws SecurityException {
        if (url == null || url.isBlank()) {
            throw new SecurityException("URL cannot be empty");
        }
        // 1. scheme
        var lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new SecurityException("Only http/https URLs allowed");
        }
        // 2. host extraction + reject userinfo
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new SecurityException("Invalid URL: " + e.getMessage());
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("URL userinfo not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("Cannot extract host from URL");
        }
        host = host.toLowerCase();

        // 3. localhost variants
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.equals("0.0.0.0") || host.equals("[::]")) {
            throw new SecurityException("Blocked local host: " + host);
        }

        // 4. DNS resolve + private IP check
        try {
            var addresses = InetAddress.getAllByName(host);
            for (var addr : addresses) {
                if (isPrivateAddress(addr)) {
                    throw new SecurityException("Blocked private/local IP for host '" + host + "': " + addr.getHostAddress());
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("DNS resolution failed for host: " + host);
        }

        // 5. allowlist
        if (allowedDomains.isEmpty()) {
            throw new SecurityException("No allowed domains configured");
        }
        if (!hostMatchesAllowlist(host)) {
            throw new SecurityException("Host '" + host + "' not in allowed domains");
        }
    }

    private boolean isPrivateAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()
                || isCgn(addr)
                || isIpv6Ula(addr)
                || isMappedPrivateV4(addr);
    }

    private boolean isIpv6Ula(InetAddress addr) {
        var bytes = addr.getAddress();
        if (bytes.length != 16) return false;
        return (bytes[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private boolean isCgn(InetAddress addr) {
        var bytes = addr.getAddress();
        if (bytes.length != 4) return false;
        int a = bytes[0] & 0xFF, b = bytes[1] & 0xFF;
        return a == 100 && b >= 64 && b <= 127; // 100.64.0.0/10
    }

    private boolean isMappedPrivateV4(InetAddress addr) {
        var bytes = addr.getAddress();
        if (bytes.length != 16) return false;
        // ::ffff:x.x.x.x — first 10 bytes 0, bytes 10-11 = 0xFF
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) return false;
        var v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        try {
            return isPrivateAddress(InetAddress.getByAddress(v4));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hostMatchesAllowlist(String host) {
        for (var domain : allowedDomains) {
            var d = domain.toLowerCase();
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    // --- Command Risk Classification ---

    public CommandRisk classifyCommand(String command) {
        var base = command.strip().split("\\s+")[0];
        // strip path prefix
        var idx = base.lastIndexOf('/');
        if (idx >= 0) base = base.substring(idx + 1);
        idx = base.lastIndexOf('\\');
        if (idx >= 0) base = base.substring(idx + 1);

        if (HIGH_RISK.contains(base)) return CommandRisk.HIGH;
        if (MEDIUM_RISK.contains(base)) return CommandRisk.MEDIUM;
        return CommandRisk.LOW;
    }

    // --- Environment Sanitization ---

    public Map<String, String> sanitizedEnv() {
        var allowed = isWindows() ? WIN_ENV : UNIX_ENV;
        return System.getenv().entrySet().stream()
                .filter(e -> allowed.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public Path workspaceRoot() { return workspaceRoot; }
}
