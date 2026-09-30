package com.github.guoyyit.accountlifecyclejava.store;

import com.github.guoyyit.accountlifecyclejava.engine.Clock;
import com.github.guoyyit.accountlifecyclejava.event.Event;
import com.github.guoyyit.accountlifecyclejava.event.EventCodec;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class DurableLog {

    private final Path file;
    private final Clock clock;
    private long sequence;
    private Predicate<EventType> crashBeforeFsync;
    private Predicate<EventType> crashAfterFsync;
    private volatile Consumer<Event> applier;

    public DurableLog(Path directory, Clock clock) {
        this.clock = clock;
        try {
            Files.createDirectories(directory);
            this.file = directory.resolve("lifecycle.log");
            if (Files.notExists(this.file)) {
                Files.createFile(this.file);
            }
            this.sequence = readAll().stream().mapToLong(Event::sequence).max().orElse(0L);
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化持久日志: " + directory, e);
        }
    }

    public synchronized Event append(EventType type, String accountId, String payloadJson) {
        long nextSequence = sequence + 1;
        Event event = new Event(nextSequence, clock.nowMillis(), type, accountId, payloadJson);
        String line = EventCodec.encode(event) + System.lineSeparator();
        if (crashBeforeFsync != null && crashBeforeFsync.test(type)) {
            throw new LifecycleException("模拟崩溃：事件 " + type + " 在落盘前中止");
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var buffer = java.nio.ByteBuffer.wrap(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("持久日志追加失败", e);
        }
        this.sequence = nextSequence;
        if (crashAfterFsync != null && crashAfterFsync.test(type)) {
            throw new LifecycleException("模拟崩溃：事件 " + type + " 已落盘后中止");
        }
        if (applier != null) {
            applier.accept(event);
        }
        return event;
    }

    public List<Event> readAll() {
        try {
            if (Files.notExists(file)) {
                return List.of();
            }
            return Files.readAllLines(file).stream()
                    .filter(l -> !l.isBlank())
                    .map(EventCodec::decode)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("持久日志读取失败", e);
        }
    }

    public synchronized long lastSequence() {
        return sequence;
    }

    public synchronized void setCrashBeforeFsync(Predicate<EventType> gate) {
        this.crashBeforeFsync = gate;
    }

    public void setApplier(Consumer<Event> eventConsumer) {
        this.applier = eventConsumer;
    }

    public synchronized void setCrashAfterFsync(Predicate<EventType> gate) {
        this.crashAfterFsync = gate;
    }

    public synchronized void clearFaults() {
        this.crashBeforeFsync = null;
        this.crashAfterFsync = null;
    }
}
