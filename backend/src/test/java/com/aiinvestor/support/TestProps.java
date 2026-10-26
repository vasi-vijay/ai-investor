package com.aiinvestor.support;

import com.aiinvestor.config.AppProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

/** Builds AppProperties exactly as Spring would (defaults + overrides) for plain unit tests. */
public final class TestProps {
    private TestProps() {}

    public static AppProperties defaults() { return with(Map.of()); }

    public static AppProperties with(Map<String, String> overrides) {
        Map<String, Object> m = new HashMap<>(overrides);
        m.putIfAbsent("app.data.source", "MOCK");
        m.putIfAbsent("app.ai.provider", "MOCK_HEURISTIC");
        return new Binder(new MapConfigurationPropertySource(m)).bind("app", AppProperties.class).get();
    }
}
