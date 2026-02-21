package com.javaclaw.skills;

import java.util.List;

/**
 * A Skill packages a system prompt + optional tool subset, triggered by a slash command.
 */
public record Skill(
    String name,
    String trigger,
    String systemPrompt,
    List<String> tools
) {}
