package com.javaclaw.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsValidSkill() throws IOException {
        Files.writeString(tempDir.resolve("review.yaml"),
                "name: Review\ntrigger: review\nsystem_prompt: You review code.\ntools:\n  - file_read\n");
        var skills = SkillLoader.loadFrom(tempDir);
        assertEquals(1, skills.size());
        assertEquals("review", skills.get(0).trigger());
        assertEquals("You review code.", skills.get(0).systemPrompt());
        assertEquals(1, skills.get(0).tools().size());
    }

    @Test
    void skipsEmptyYaml() throws IOException {
        Files.writeString(tempDir.resolve("empty.yaml"), "");
        var skills = SkillLoader.loadFrom(tempDir);
        assertTrue(skills.isEmpty());
    }

    @Test
    void skipsMalformedYaml() throws IOException {
        Files.writeString(tempDir.resolve("bad.yaml"), "{{invalid yaml");
        var skills = SkillLoader.loadFrom(tempDir);
        assertTrue(skills.isEmpty());
    }

    @Test
    void skipsMissingTrigger() throws IOException {
        Files.writeString(tempDir.resolve("notrigger.yaml"), "name: Broken\n");
        var skills = SkillLoader.loadFrom(tempDir);
        assertTrue(skills.isEmpty());
    }

    @Test
    void missingSystemPromptIsNull() throws IOException {
        Files.writeString(tempDir.resolve("minimal.yaml"), "name: Min\ntrigger: min\n");
        var skills = SkillLoader.loadFrom(tempDir);
        assertEquals(1, skills.size());
        assertNull(skills.get(0).systemPrompt());
    }

    @Test
    void returnsEmptyForNonexistentDir() {
        var skills = SkillLoader.loadFrom(Path.of("/nonexistent/path"));
        assertTrue(skills.isEmpty());
    }
}
