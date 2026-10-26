package com.aiinvestor.support;

import com.aiinvestor.ai.AiAnalyst;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class AiTestConfig {
    @Bean @Primary ScriptedAnalyst scriptedAnalyst() { return new ScriptedAnalyst(); }
}
