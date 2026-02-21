package com.javaclaw.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {

    @TempDir
    Path tempDir;

    private SecurityPolicy policy() {
        return new SecurityPolicy(tempDir, true, 100, Set.of("example.com", "api.github.com"));
    }

    // --- validatePath ---

    @Test
    void validatePathNormal() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello");
        var result = policy().validatePath("test.txt", true);
        assertTrue(result.startsWith(tempDir.toRealPath()));
    }

    @Test
    void validatePathRejectsTraversal() {
        assertThrows(SecurityException.class, () -> policy().validatePath("../etc/passwd", false));
    }

    @Test
    void validatePathRejectsNullBytes() {
        assertThrows(SecurityException.class, () -> policy().validatePath("file\0.txt", false));
    }

    @Test
    void validatePathRejectsLargeFile() throws IOException {
        var big = tempDir.resolve("big.bin");
        try (var out = Files.newOutputStream(big)) {
            out.write(new byte[10 * 1024 * 1024 + 1]);
        }
        assertThrows(SecurityException.class, () -> policy().validatePath("big.bin", true));
    }

    @Test
    void validatePathAllowsNewFile() {
        assertDoesNotThrow(() -> policy().validatePath("newfile.txt", false));
    }

    // --- validateDomain ---

    @Test
    void validateDomainAllowsWhitelisted() {
        assertDoesNotThrow(() -> policy().validateDomain("https://example.com/path"));
    }

    @Test
    void validateDomainAllowsSubdomain() {
        assertDoesNotThrow(() -> policy().validateDomain("https://api.github.com/repos"));
    }

    @Test
    void validateDomainRejectsNonAllowlisted() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("https://evil.com"));
    }

    @Test
    void validateDomainRejectsLocalhost() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("http://localhost:8080"));
    }

    @Test
    void validateDomainRejectsPrivateIP() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("http://10.0.0.1"));
    }

    @Test
    void validateDomainRejectsLoopback() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("http://127.0.0.1"));
    }

    @Test
    void validateDomainRejectsFtp() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("ftp://example.com"));
    }

    @Test
    void validateDomainRejectsUserinfo() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("https://admin:pass@example.com"));
    }

    @Test
    void validateDomainRejectsIpv6Ula() {
        assertThrows(SecurityException.class, () -> policy().validateDomain("http://[fc00::1]"));
    }

    @Test
    void validateDomainRejectsEmpty() {
        assertThrows(SecurityException.class, () -> policy().validateDomain(""));
    }

    // --- classifyCommand ---

    @Test
    void classifyCommandLow() {
        assertEquals(CommandRisk.LOW, policy().classifyCommand("ls -la"));
        assertEquals(CommandRisk.LOW, policy().classifyCommand("cat file.txt"));
    }

    @Test
    void classifyCommandMedium() {
        assertEquals(CommandRisk.MEDIUM, policy().classifyCommand("git commit -m msg"));
        assertEquals(CommandRisk.MEDIUM, policy().classifyCommand("npm install express"));
    }

    @Test
    void classifyCommandHigh() {
        assertEquals(CommandRisk.HIGH, policy().classifyCommand("rm -rf /"));
        assertEquals(CommandRisk.HIGH, policy().classifyCommand("sudo apt install"));
    }

    // --- sanitizedEnv ---

    @Test
    void sanitizedEnvOnlyContainsAllowedKeys() {
        var env = policy().sanitizedEnv();
        var isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        var forbidden = Set.of("API_KEY", "SECRET", "TOKEN", "AWS_SECRET_ACCESS_KEY");
        for (var key : env.keySet()) {
            assertFalse(forbidden.contains(key), "Should not contain: " + key);
        }
        // Must contain PATH (present on all OS)
        if (System.getenv("PATH") != null) {
            assertTrue(env.containsKey("PATH"));
        }
    }
}
