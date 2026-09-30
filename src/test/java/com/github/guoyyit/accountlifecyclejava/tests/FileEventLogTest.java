package com.github.guoyyit.accountlifecyclejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;
import com.github.guoyyit.accountlifecyclejava.storage.FileEventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileEventLogTest {

    @TempDir
    Path tempDir;

    @Test
    void tornTailLineFromCrashIsSkippedOnReplay() throws IOException {
        Path logFile = tempDir.resolve("events.log");
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        FileEventLog first = new FileEventLog(logFile, mapper);
        LifecycleEvent committed = event("e-1");
        first.append(committed);

        // 模拟在写入第二行的中途崩溃：只留下无法解析的残行。
        byte[] torn = (mapper.writeValueAsString(event("e-2")).substring(0, 40) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(logFile, torn, StandardOpenOption.APPEND);

        FileEventLog reopened = new FileEventLog(logFile, mapper);
        List<LifecycleEvent> events = reopened.readAll();

        assertEquals(1, events.size());
        assertEquals("e-1", events.get(0).accountId());

        // 崩溃后继续追加新事件，不受残行影响，且重放仍只含完整行。
        reopened.append(event("e-3"));
        assertEquals(2, reopened.readAll().size());
    }

    private LifecycleEvent event(String id) {
        return new LifecycleEvent(
                UUID.randomUUID().toString(),
                EventType.ACTIVITY_REPORTED,
                id, Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null,
                Instant.parse("2026-01-01T00:00:00Z").toString(),
                null);
    }
}
