package com.javaclaw.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("Duplicate tool: " + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public Collection<Tool> all() {
        return tools.values();
    }
}
