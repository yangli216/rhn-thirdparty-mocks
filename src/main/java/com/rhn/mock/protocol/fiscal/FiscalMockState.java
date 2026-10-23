package com.rhn.mock.protocol.fiscal;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 财政票据内存态：票据、幂等键索引、回调投递记录。重启或 {@code POST /mock/reset} 清空。 */
@Component
public class FiscalMockState {
    private final Map<String, FiscalReceipt> byExternalNo = new LinkedHashMap<>();
    private final Map<String, String> byRequestNo = new LinkedHashMap<>();
    private final Map<String, String> issueKeys = new LinkedHashMap<>();
    private final Map<String, String> redKeys = new LinkedHashMap<>();
    private final List<CallbackDelivery> deliveries = new ArrayList<>();
    private final AtomicLong numberSequence = new AtomicLong(1859230L);
    private final AtomicLong idSequence = new AtomicLong();

    /** 一次回调投递记录。 */
    public record CallbackDelivery(String time, String messageId, String externalReceiptNo, String operation,
                                   String mode, String url, int httpStatus, String error, String body) {
    }

    public synchronized FiscalReceipt byExternalNo(String no) {
        return no == null ? null : byExternalNo.get(no);
    }

    public synchronized FiscalReceipt byRequestNo(String no) {
        String ext = no == null ? null : byRequestNo.get(no);
        return ext == null ? null : byExternalNo.get(ext);
    }

    public synchronized FiscalReceipt byIssueKey(String key) {
        String ext = key == null ? null : issueKeys.get(key);
        return ext == null ? null : byExternalNo.get(ext);
    }

    public synchronized FiscalReceipt byRedKey(String key) {
        String ext = key == null ? null : redKeys.get(key);
        return ext == null ? null : byExternalNo.get(ext);
    }

    public synchronized FiscalReceipt find(String requestNo, String externalNo) {
        FiscalReceipt receipt = byExternalNo(externalNo);
        return receipt != null ? receipt : byRequestNo(requestNo);
    }

    public synchronized void put(FiscalReceipt receipt, boolean indexIssueKey) {
        byExternalNo.put(receipt.externalReceiptNo(), receipt);
        if (receipt.receiptRequestNo() != null) byRequestNo.putIfAbsent(receipt.receiptRequestNo(), receipt.externalReceiptNo());
        if (indexIssueKey && receipt.idempotencyKey() != null) {
            (receipt.red() ? redKeys : issueKeys).put(receipt.idempotencyKey(), receipt.externalReceiptNo());
        }
    }

    public synchronized List<FiscalReceipt> receipts() {
        return new ArrayList<>(byExternalNo.values());
    }

    public long nextNumber() {
        return numberSequence.incrementAndGet();
    }

    public long nextId() {
        return idSequence.incrementAndGet();
    }

    public synchronized void addDelivery(CallbackDelivery delivery) {
        deliveries.add(delivery);
    }

    public synchronized List<CallbackDelivery> deliveries() {
        return new ArrayList<>(deliveries);
    }

    public synchronized void reset() {
        byExternalNo.clear();
        byRequestNo.clear();
        issueKeys.clear();
        redKeys.clear();
        deliveries.clear();
        numberSequence.set(1859230L);
        idSequence.set(0);
    }
}
