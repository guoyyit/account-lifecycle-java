package com.github.guoyyit.accountlifecyclejava.store;

import com.github.guoyyit.accountlifecyclejava.engine.Clock;
import com.github.guoyyit.accountlifecyclejava.event.Event;
import com.github.guoyyit.accountlifecyclejava.event.EventCodec;
import com.github.guoyyit.accountlifecyclejava.event.EventType;
import com.github.guoyyit.accountlifecyclejava.lifecycle.LifecycleException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
    private long tornTailBytesDiscarded;

    public DurableLog(Path directory, Clock clock) {
        this.clock = clock;
        try {
            Files.createDirectories(directory);
            this.file = directory.resolve("lifecycle.log");
            if (Files.notExists(this.file)) {
                Files.createFile(this.file);
            }
            ParsedLog parsed = repairTornTailAndParse();
            this.sequence = parsed.events.stream().mapToLong(Event::sequence).max().orElse(0L);
            this.tornTailBytesDiscarded = parsed.tornTailBytes;
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
            return List.copyOf(parseTolerant(Files.readAllBytes(file)).events);
        } catch (IOException e) {
            throw new UncheckedIOException("持久日志读取失败", e);
        }
    }

    public long tornTailBytesDiscarded() {
        return tornTailBytesDiscarded;
    }

    private ParsedLog repairTornTailAndParse() throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ParsedLog parsed = parseTolerant(bytes);
        boolean needsTruncate = parsed.validLength < bytes.length;
        boolean needsNormalize = parsed.trailingEventMissingNewline
                && parsed.validLength == bytes.length;
        if (!needsTruncate && !needsNormalize) {
            return parsed;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            if (needsTruncate) {
                channel.truncate(parsed.validLength);
            }
            if (needsNormalize) {
                channel.position(channel.size());
                channel.write(java.nio.ByteBuffer.wrap(
                        System.lineSeparator().getBytes(StandardCharsets.UTF_8)));
            }
            channel.force(true);
        }
        return parsed;
    }

    private static ParsedLog parseTolerant(byte[] bytes) {
        List<Event> events = new ArrayList<>();
        int length = bytes.length;
        int lineStart = 0;
        int validEnd = 0;
        int index = 0;
        while (index <= length) {
            if (index < length && bytes[index] != '\n') {
                index++;
                continue;
            }
            int lineEnd = index;
            String raw = new String(bytes, lineStart, lineEnd - lineStart,
                    StandardCharsets.UTF_8);
            String text = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int afterLine = index == length ? length : index + 1;
            if (text.isBlank()) {
                validEnd = afterLine;
            } else {
                Event decoded;
                try {
                    decoded = EventCodec.decode(text);
                } catch (RuntimeException ex) {
                    if (containsDecodableNonBlankLine(bytes, afterLine)) {
                        throw new LifecycleException(
                                "事件日志中部出现损坏记录，无法安全重建: 偏移 " + lineStart, ex);
                    }
                    return new ParsedLog(events, validEnd, length - validEnd, false);
                }
                events.add(decoded);
                validEnd = afterLine;
            }
            lineStart = afterLine;
            if (index == length) {
                break;
            }
            index++;
        }
        boolean trailingEventMissingNewline = !events.isEmpty() && length > 0
                && bytes[length - 1] != '\n';
        return new ParsedLog(events, validEnd, length - validEnd, trailingEventMissingNewline);
    }

    private static boolean containsDecodableNonBlankLine(byte[] bytes, int from) {
        int length = bytes.length;
        int lineStart = from;
        for (int index = from; index <= length; index++) {
            if (index < length && bytes[index] != '\n') {
                continue;
            }
            String text = new String(bytes, lineStart, index - lineStart,
                    StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                try {
                    EventCodec.decode(text);
                    return true;
                } catch (RuntimeException ignored) {
                    // 继续向后检查：若损坏区之后还存在可解码记录，则属于中部损坏。
                }
            }
            lineStart = index + 1;
            if (index == length) {
                break;
            }
        }
        return false;
    }

    private record ParsedLog(List<Event> events, int validLength, long tornTailBytes,
                             boolean trailingEventMissingNewline) {
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
