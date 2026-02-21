package com.javaclaw.skills;

import java.util.LinkedHashMap;
import java.util.Map;

public class SkillRegistry {

    private final Map<String, Skill> byTrigger = new LinkedHashMap<>();

    public void register(Skill skill) {
        var key = skill.trigger().startsWith("/") ? skill.trigger().substring(1) : skill.trigger();
        byTrigger.put(key, skill);
    }

    public Skill match(String message) {
        if (message == null || !message.startsWith("/")) return null;
        var cmd = message.split("\\s", 2)[0].substring(1);
        return byTrigger.get(cmd);
    }
}
