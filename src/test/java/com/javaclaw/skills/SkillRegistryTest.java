package com.javaclaw.skills;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private final SkillRegistry registry = new SkillRegistry();

    @Test
    void matchesSlashCommand() {
        registry.register(new Skill("Review", "review", "You are a reviewer.", List.of("file_read")));
        var skill = registry.match("/review some code");
        assertNotNull(skill);
        assertEquals("Review", skill.name());
    }

    @Test
    void matchesTriggerWithSlashPrefix() {
        // Issue 1: YAML may define trigger as "/review"
        registry.register(new Skill("Review", "/review", "prompt", List.of()));
        var skill = registry.match("/review code");
        assertNotNull(skill);
    }

    @Test
    void returnsNullForNoMatch() {
        registry.register(new Skill("Review", "review", "prompt", List.of()));
        assertNull(registry.match("/unknown hello"));
    }

    @Test
    void returnsNullForNonSlashMessage() {
        registry.register(new Skill("Review", "review", "prompt", List.of()));
        assertNull(registry.match("hello"));
    }

    @Test
    void returnsNullForNull() {
        assertNull(registry.match(null));
    }

    @Test
    void matchesTriggerOnly() {
        registry.register(new Skill("Review", "review", "prompt", List.of()));
        var skill = registry.match("/review");
        assertNotNull(skill);
    }
}
