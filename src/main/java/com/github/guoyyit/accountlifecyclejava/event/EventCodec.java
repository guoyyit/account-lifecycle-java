package com.github.guoyyit.accountlifecyclejava.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;

public final class EventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EventCodec() {
    }

    public static String encode(Event event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new LifecycleException("事件编码失败", e);
        }
    }

    public static Event decode(String line) {
        try {
            return MAPPER.readValue(line, Event.class);
        } catch (Exception e) {
            throw new LifecycleException("事件日志行损坏，无法解码: " + line, e);
        }
    }
}
