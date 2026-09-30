package com.github.guoyyit.accountlifecyclejava.event;

import java.util.List;

/**
 * 本地持久化事件日志。append 在崩溃语义下保证：写入 fsync 成功后不丢；
 * 崩溃时未完成的一行要么完整存在，要么不存在，重放时跳过不完整尾行。
 */
public interface EventLog {

    void append(LifecycleEvent event);

    List<LifecycleEvent> readAll();
}
