package com.rhn.mock.core;

import com.rhn.mock.core.config.MockCoreProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** 已收业务报文环形缓冲，供 {@code GET /mock/requests} 断言与向导报文对照。 */
@Component
public class ExchangeLog {
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int capacity;

    public ExchangeLog(MockCoreProperties properties) {
        this.capacity = Math.max(1, properties.requestsBufferSize());
    }

    public synchronized void add(Entry entry) {
        if (entries.size() >= capacity) entries.removeFirst();
        entries.addLast(entry);
    }

    /** 按时间倒序返回，可按协议与操作码过滤（为空表示不筛）。 */
    public synchronized List<Entry> query(String protocol, String op, int limit) {
        List<Entry> all = new ArrayList<>(entries);
        Collections.reverse(all);
        int size = limit <= 0 ? 100 : limit;
        return all.stream()
                .filter(entry -> protocol == null || protocol.isBlank() || protocol.equals(entry.protocol()))
                .filter(entry -> op == null || op.isBlank() || op.equals(entry.op()))
                .limit(size)
                .toList();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /**
     * 一条已收报文。
     *
     * @param protocol   协议标识
     * @param op         操作码：CHS=infno、alipay=method、wxpay=api 路径
     * @param refId      业务参考号：msgid / out_trade_no
     */
    public record Entry(String time, String protocol, String op, String refId,
                        int httpStatus, String request, String response) {
    }
}
