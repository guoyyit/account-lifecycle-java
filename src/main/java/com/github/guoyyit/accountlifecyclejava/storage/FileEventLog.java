package com.github.guoyyit.accountlifecyclejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.accountlifecyclejava.event.EventLog;
import com.github.guoyyit.accountlifecyclejava.event.LifecycleEvent;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

public class FileEventLog implements EventLog {

    private final Path logFile;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public FileEventLog(Path logFile, ObjectMapper objectMapper) {
        this.logFile = logFile;
        this.objectMapper = objectMapper;
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            if (Files.notExists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("初始化事件日志失败: " + logFile, e);
        }
    }

    @Override
    public synchronized void append(LifecycleEvent event) {
        byte[] line;
        try {
            line = (objectMapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化事件失败: " + event, e);
        }
        // 整行一次 write + fsync：调用返回后事件不丢；
        // 若在中途崩溃，写出的是无法解析的残行，重放时丢弃，迁移不会被部分应用。
        synchronized (writeLock) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    logFile.toFile(), true)) {
                out.write(line);
                out.flush();
                out.getFD().sync();
            } catch (IOException e) {
                throw new UncheckedIOException("追加事件失败: " + event, e);
            }
        }
    }

    @Override
    public List<LifecycleEvent> readAll() {
        try {
            if (Files.notExists(logFile)) {
                return List.of();
            }
            try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
                return lines
                        .filter(line -> !line.isBlank())
                        .map(this::parseOrNull)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取事件日志失败: " + logFile, e);
        }
    }

    private LifecycleEvent parseOrNull(String line) {
        try {
            return objectMapper.readValue(line, LifecycleEvent.class);
        } catch (IOException e) {
            // 崩溃残留的不完整尾行：无法解析即跳过，绝不据此应用半次迁移。
            return null;
        }
    }
}
