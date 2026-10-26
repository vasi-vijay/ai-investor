package com.aiinvestor.strategy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StrategyRegistry {
    private final Map<String, Strategy> byName = new LinkedHashMap<>();

    public StrategyRegistry(List<Strategy> strategies) {
        for (Strategy s : strategies) byName.put(s.name(), s);
    }

    public List<Strategy> all() { return List.copyOf(byName.values()); }

    public Strategy get(String name) {
        Strategy s = byName.get(name);
        if (s == null) throw new IllegalArgumentException("unknown strategy " + name);
        return s;
    }
}
