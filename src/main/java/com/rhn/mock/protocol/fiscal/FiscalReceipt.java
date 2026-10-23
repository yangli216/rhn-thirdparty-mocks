package com.rhn.mock.protocol.fiscal;

import java.math.BigDecimal;
import java.time.Instant;

/** 一张票据（蓝票或红票）的内存记录；状态变更都在 {@link FiscalMockState} 的锁内进行。 */
public class FiscalReceipt {
    public enum Status { PROCESSING, ISSUED, FAILED, VOIDED }

    final String externalReceiptNo;
    final boolean red;
    final String receiptRequestNo;
    final String idempotencyKey;
    final String payerName;
    final BigDecimal amount;
    final String fiscalCode;
    final String fiscalNumber;
    final String verificationCode;
    final String verifyUrl;
    final String originalExternalReceiptNo;
    Instant issuedAt;
    Instant voidedAt;
    Status status;
    int queryCount;
    String errorCode;
    String errorMessage;
    String redExternalReceiptNo;
    String voidIdempotencyKey;
    String voidReason;

    FiscalReceipt(String externalReceiptNo, boolean red, String receiptRequestNo, String idempotencyKey,
                  String payerName, BigDecimal amount, String fiscalCode, String fiscalNumber,
                  String verificationCode, String verifyUrl, String originalExternalReceiptNo, Status status) {
        this.externalReceiptNo = externalReceiptNo;
        this.red = red;
        this.receiptRequestNo = receiptRequestNo;
        this.idempotencyKey = idempotencyKey;
        this.payerName = payerName;
        this.amount = amount;
        this.fiscalCode = fiscalCode;
        this.fiscalNumber = fiscalNumber;
        this.verificationCode = verificationCode;
        this.verifyUrl = verifyUrl;
        this.originalExternalReceiptNo = originalExternalReceiptNo;
        this.status = status;
    }

    public String externalReceiptNo() { return externalReceiptNo; }
    public boolean red() { return red; }
    public String receiptRequestNo() { return receiptRequestNo; }
    public String idempotencyKey() { return idempotencyKey; }
    public String payerName() { return payerName; }
    public BigDecimal amount() { return amount; }
    public String fiscalCode() { return fiscalCode; }
    public String fiscalNumber() { return fiscalNumber; }
    public String verificationCode() { return verificationCode; }
    public String verifyUrl() { return verifyUrl; }
    public String originalExternalReceiptNo() { return originalExternalReceiptNo; }
    public Instant issuedAt() { return issuedAt; }
    public Instant voidedAt() { return voidedAt; }
    public Status status() { return status; }
    public int queryCount() { return queryCount; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public String redExternalReceiptNo() { return redExternalReceiptNo; }
}
