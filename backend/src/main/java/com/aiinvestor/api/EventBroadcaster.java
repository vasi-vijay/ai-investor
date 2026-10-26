package com.aiinvestor.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Pushes lightweight "something changed" events to authenticated dashboards (the /ws/** path sits behind Spring
 * Security's session authentication). Payloads carry no secrets; clients refetch data through the REST API.
 * Inbound messages are ignored: the socket is server→client only.
 */
@Component
public class EventBroadcaster extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper;

    public EventBroadcaster(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public void afterConnectionEstablished(WebSocketSession s) { sessions.add(s); }
    @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status) { sessions.remove(s); }
    @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) { /* server→client only */ }

    public void publish(String type, Map<String, Object> payload) {
        if (sessions.isEmpty()) return;
        String json;
        try { json = mapper.writeValueAsString(Map.of("type", type, "data", payload)); } catch (JsonProcessingException e) { return; }
        for (WebSocketSession s : sessions) {
            try { synchronized (s) { if (s.isOpen()) s.sendMessage(new TextMessage(json)); } }
            catch (IOException | RuntimeException e) { sessions.remove(s); log.debug("dropped websocket client"); }
        }
    }

    public int clients() { return sessions.size(); }

    @Configuration
    @EnableWebSocket
    static class WsConfig implements WebSocketConfigurer {
        private final EventBroadcaster handler;
        private final com.aiinvestor.config.AppProperties props;
        WsConfig(EventBroadcaster handler, com.aiinvestor.config.AppProperties props) { this.handler = handler; this.props = props; }

        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
            r.addHandler(handler, "/ws/events").setAllowedOrigins(props.security().allowedOrigins().toArray(new String[0]));
        }
    }
}
