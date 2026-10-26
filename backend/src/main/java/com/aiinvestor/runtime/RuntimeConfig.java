package com.aiinvestor.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RuntimeConfig {
    @Bean TransientStore transientStore(ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate t = redis.getIfAvailable();
        return t == null ? new InMemoryTransientStore() : new RedisTransientStore(t);
    }
}
