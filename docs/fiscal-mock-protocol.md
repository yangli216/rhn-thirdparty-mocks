# 财政电子票据（fiscal）Mock 协议

> **协议是自拟的**。RHN 目前只有进程内 `MockFiscalReceiptClient`（`rhn.billing.fiscal.mock-enabled=true`）和一律拒绝的 `StandardFiscalReceiptClient`，没有 HTTP 客户端，真实省财政平台的报文也未提供。本协议把 RHN 端口 `NationalFiscalReceiptClient` 与 `FiscalModels` 的字段**原样搬到 HTTP/JSON 上**，不增加 RHN 不用的字段（因此没有 PDF 内容字段：RHN 只存 `verifyUrl`）。

## 1. 形态

- 业务端点：`POST /fiscal/v1/receipts/{issue|query|void|redFlush}`（基址 `mock.fiscal.base-path`，默认 `/fiscal`），`Content-Type: application/json;charset=UTF-8`，与 8080/5173/18090 共用同一个端口。
- 凭据：请求头 `X-Client-Id: mock-fiscal-client` / `X-Client-Secret: mock-fiscal-secret`（`mock.fiscal.client-*`；`clientSecret` 置空关闭校验）。缺失或不符 → HTTP 401。
- 请求体非 JSON 对象 → 400；未知操作 → 404。
- **业务成功与业务拒绝都是 HTTP 200**，由响应体 `success` 区分（对应 `FiscalModels.*Response.success`）。时间字段是 ISO-8601 UTC（`Instant.toString()`，如 `2026-10-19T08:30:00.123Z`），金额是 JSON 数字（元，两位小数）。
- 内存态，重启或 `POST /mock/reset` 清空。

## 2. 操作

### 2.1 issue 开具（`FiscalIssueRequest` → `FiscalIssueResponse`）

请求字段（同 `FiscalIssueRequest`）：`receiptId, settlementId, receiptRequestNo, idempotencyKey, receiptType, issueChannel, fiscalAuthorityCode, payerName, payerIdentityDigest, totalAmount, roundingAmount, insuranceAmount, personalAccountAmount, patientAmount, otherFundAmount, currencyCode, items[{lineNo,categoryCode,itemCode,itemName,quantity,amount}], correlationId`。

必填：`receiptRequestNo`、`idempotencyKey`、`totalAmount`（>0）。校验（与 RHN 设计文档“开票资金分摊 / 舍入差额”约束一致）：
- `items[].amount` 之和 + `roundingAmount` = `totalAmount`，否则 `FISCAL_AMOUNT_MISMATCH`；
- 若给了资金分项，`insuranceAmount + personalAccountAmount + patientAmount + otherFundAmount` 等于 `totalAmount`（或加 `roundingAmount` 后等于），任一为负也拒绝。

响应（同 `FiscalIssueResponse`）：`success, outcome, externalReceiptNo, fiscalCode, fiscalNumber, verificationCode, verifyUrl, issuedAt, errorCode, errorMessage`。

| 情形 | success | outcome | 其他 |
|---|---|---|---|
| 出票成功 | true | `ISSUED` | 四要素：`fiscalCode`(10 位：区划4+`0601`+年份2)、`fiscalNumber`(10 位顺序码)、`verificationCode`(6 位)、`verifyUrl`；`issuedAt`；`errorCode/errorMessage` 为 null |
| 处理中（异步） | false | `PENDING` | 有 `externalReceiptNo`，`errorCode=FISCAL_PROCESSING`；四要素为 null |
| 平台拒绝 | false | `FAILED` | `errorCode`（见 §3），无 `externalReceiptNo` |

**幂等**：以 `idempotencyKey` 或 `receiptRequestNo` 命中已有票据时返回**同一张票**（同一 `externalReceiptNo / fiscalNumber / verificationCode`，处理中则仍是处理中）；同键但 `receiptRequestNo` 或 `totalAmount` 不同 → `FISCAL_IDEMPOTENCY_CONFLICT`。失败（`FAILED`）的开具不占用幂等键，可重试。

`verifyUrl` 指向本服务的公共查验页 `GET /fiscal/verify?bill_code=&bill_no=&check_code=`（JSON，三元组与票据一致返回 200 `verified=true`，否则 404）。前缀 `mock.fiscal.verify-base-url`，换端口时同步修改。

### 2.2 query 查询（`FiscalIssueResponse`）

请求：`receiptRequestNo`、`externalReceiptNo`（至少一个，后者优先）、`correlationId`。响应形状同 issue，`outcome` 取平台**明确的业务状态**：

| 票据状态 | success | outcome |
|---|---|---|
| 处理中（未到可出票的查询次数） | false | `PENDING`（`FISCAL_PROCESSING`） |
| 已出票 | true | `ISSUED`（含四要素） |
| 已作废 | true | `VOIDED`（仍带原四要素，`issuedAt` 为出票时间） |
| 红字票据（用红票号查） | true | `RED_FLUSHED` |
| 平台处理失败 | false | `FAILED` |
| 查无此票 | false | `FAILED`，`FISCAL_RECEIPT_NOT_FOUND` |

> 与 `ProvincialFiscalReceiptAdapter.outcome()` 的约束一致：`ISSUED/VOIDED/RED_FLUSHED ⇔ success=true`，成功与业务状态不会矛盾。

### 2.3 void 作废（`FiscalVoidRequest` → `FiscalVoidResponse`）

请求：`receiptId, receiptRequestNo, idempotencyKey, externalReceiptNo, reason, correlationId`；`idempotencyKey`、`externalReceiptNo` 必填。响应：`success, externalReceiptNo, voidedAt, errorCode, errorMessage`。

规则：只有 `ISSUED` 的蓝票能作废；同一 `idempotencyKey` 重放返回同一 `voidedAt`；换键再作废 → `FISCAL_ALREADY_VOIDED`；已冲红 → `FISCAL_ALREADY_RED_FLUSHED`；处理中 / 失败 → `FISCAL_NOT_ISSUED`；红票 → `FISCAL_VOID_NOT_ALLOWED`。

### 2.4 redFlush 红字冲红（`FiscalRedFlushRequest` → `FiscalRedFlushResponse`）

请求：`receiptId, receiptRequestNo, idempotencyKey, originalExternalReceiptNo, originalFiscalCode, originalFiscalNumber, reason, correlationId`；`idempotencyKey`、`originalExternalReceiptNo` 必填；`originalFiscalCode/Number` 可空，给了必须与原票一致（否则 `FISCAL_RECEIPT_MISMATCH`）。响应：`success, redExternalReceiptNo, redFiscalCode, redFiscalNumber, verificationCode, verifyUrl, issuedAt, errorCode, errorMessage`。

规则：
- 只能对 `ISSUED` 的蓝票冲红；**已作废不可冲红**（`FISCAL_RECEIPT_VOIDED`）；处理中 / 失败 `FISCAL_NOT_ISSUED`；对红票再冲红 `FISCAL_RED_FLUSH_NOT_ALLOWED`。
- **冲红不超过原额**：RHN 的冲红请求不带金额，按原额**全额**冲红；原票已被冲红后再冲（换键）→ `FISCAL_ALREADY_RED_FLUSHED`（否则累计冲红额会超过原额）。同键重放返回同一张红票。
- 红票：`redExternalReceiptNo` 以 `RED-EINV-` 开头，`redFiscalCode` 沿用原票代码（与 RHN 进程内 mock 一致），号码为新顺序码，金额 = −原额（`GET /mock/fiscal/receipts/{ref}` 可见）。冲红后原票不可作废。

## 3. 错误码

`FISCAL_INVALID_ARGUMENT`（缺必填 / 金额非法）、`FISCAL_AMOUNT_MISMATCH`、`FISCAL_IDEMPOTENCY_CONFLICT`、`FISCAL_ISSUE_REJECTED`（FAIL 场景）、`FISCAL_PROCESSING`（处理中）、`FISCAL_RECEIPT_NOT_FOUND`、`FISCAL_NOT_ISSUED`、`FISCAL_ALREADY_VOIDED`、`FISCAL_VOID_NOT_ALLOWED`、`FISCAL_RECEIPT_VOIDED`、`FISCAL_ALREADY_RED_FLUSHED`、`FISCAL_RED_FLUSH_NOT_ALLOWED`、`FISCAL_RECEIPT_MISMATCH`，以及 `forceErrorCode` 自定义。RHN 现有代码只在结果里保存 `errorCode/errorMessage`，不依赖具体取值。

## 4. 场景与故障注入

**按付款人姓名选场景（仅 issue）**：`payerName` 以 `TEST-` 开头时：

| payerName | 行为 |
|---|---|
| `TEST-PENDING` | 开具返回 `PENDING`，之后靠 query / 管理端点 / 自动回调变为成功 |
| `TEST-FAIL` | `FAILED`，`FISCAL_ISSUE_REJECTED`，不落票 |
| `TEST-HTTP500` | HTTP 500 |
| `TEST-BADJSON` | HTTP 200，正文 `<html>…`（非 JSON） |
| `TEST-SLOW` | 延迟 `slowDelayMs`（默认 5s）后正常 |
| `TEST-TIMEOUT` | 延迟 `timeoutDelayMs`（默认 31s，超过客户端超时）后正常 |

**全局开关**：`PUT /mock/config?protocol=fiscal`：

| 字段 | 含义 |
|---|---|
| `fault` | `NONE`/`HTTP500`/`BADJSON`/`SLOW`/`TIMEOUT`，作用于全部操作，或只作用于 `faultOp`（`issue/query/void/redFlush`，空=全部） |
| `issueScenario` | 开具默认场景 `NORMAL`/`PENDING`/`FAIL`（payerName 的 `TEST-*` 优先） |
| `pendingQueries` | PENDING 票据被查询多少次仍返回处理中，之后下一次查询出票成功（默认 1；0=首次查询即成功） |
| `forceErrorCode` / `forceMessage` | 所有操作强制业务失败 |
| `forceDelayMs` / `slowDelayMs` / `timeoutDelayMs` | 延迟 |
| `clientSecret` | 凭据校验 |
| `callbackUrl` / `callbackSecret` / `autoCallback` / `callbackDelayMs` / `verifyBaseUrl` | 见 §5 |

启动默认见 `application.yml` 的 `mock.fiscal.*`；`POST /mock/reset` 复位。

## 5. 异步结果回调（mock → RHN）

RHN 已有的入站边界是进程内端口 `FiscalReceiptResultDirectory.accept(VerifiedReceiptResult)`（文档：适配器须先认证发送方、验协议签名，原始公网回调不得直达领域）。**RHN 目前没有 HTTP 回调入口**，需新增一个控制器（见 §7）。mock 按 `VerifiedReceiptResult` 的字段向 `callbackUrl` 推送：

```
POST <callbackUrl>
X-Fiscal-Timestamp: <epoch millis>
X-Fiscal-Signature: hex( HMAC-SHA256( callbackSecret, timestamp + "." + 原始请求体 ) )
{
  "fiscalAuthorityCode": "FISCAL",
  "externalMessageBusinessId": "FMSG-<票号>-<ISSUE|VOID|RED_FLUSH>-<outcome>",   // 稳定，同状态重复推送同一个号
  "commandCode": "<该操作的幂等键>",
  "receiptRequestNo": "...",
  "operation": "ISSUE | VOID | RED_FLUSH",
  "outcome": "ISSUED | PENDING | FAILED | VOIDED | RED_FLUSHED",
  "externalReceiptNo": "...", "fiscalCode": "...", "fiscalNumber": "...", "verificationCode": "...",
  "verifyUrl": "...",            // 对应 VerifiedReceiptResult.controlledObjectReference
  "issuedAt": "...Z",            // 作废时为作废时间
  "actionReason": "...",         // 仅作废
  "errorCode": "...", "errorMessage": "..."   // 仅失败 / 处理中
}
```

字段取舍：成功时才带四要素与 `issuedAt`；作废回调不带票据四要素（对应 RHN“作废成功须提供与原票据一致的外部票据号和操作时间”）。RHN 摘要还绑定“财政平台”，用 `fiscalAuthorityCode=FISCAL`（`ProvincialFiscalReceiptAdapter` 支持的取值之一）。

**触发方式**
- `autoCallback=true` 且配了 `callbackUrl`：PENDING 票据在 `callbackDelayMs` 后自动变成功并推送；
- `POST /mock/fiscal/receipts/{ref}/complete`，body `{"success":true|false,"push":true|false,"callbackUrl":"可选"}`：手动完成处理中票据并推送；
- `POST /mock/fiscal/receipts/{ref}/callback`，body `{"mode":"normal|duplicate|bad_signature","callbackUrl":"可选"}`：按票据**当前状态**（可以是已作废 / 红票）推送一次——`duplicate` 同一报文发两次（同 `externalMessageBusinessId`），`bad_signature` 用错误密钥签名（接收方应拒绝）；
- 所有投递记录：`GET /mock/fiscal/callbacks`（时间、消息号、目标、HTTP 状态或错误、报文）。接收端不可达不抛错，记录 `httpStatus=0` 与 `error`。

`{ref}` 可以是外部票据号或申请号。

## 6. 管理端点

沿用通用 `POST /mock/reset`、`GET /mock/requests?protocol=fiscal&op=issue`（`refId`=申请号 / 票号）、`GET|PUT /mock/config?protocol=fiscal`、`GET /mock/health`，外加：

| 端点 | 作用 |
|---|---|
| `GET /mock/fiscal/receipts` / `GET /mock/fiscal/receipts/{ref}` | 查看票据（状态、四要素、金额、关联红票 / 原票、`queryCount`） |
| `POST /mock/fiscal/receipts/{ref}/complete` | 完成处理中的票据（§5） |
| `POST /mock/fiscal/receipts/{ref}/callback` | 手动触发回调（§5） |
| `GET /mock/fiscal/callbacks` | 回调投递记录 |
| `GET /fiscal/verify?bill_code=&bill_no=&check_code=` | 公共查验页 |

## 7. RHN 侧需要新增 HTTP 客户端的适配点清单

1. **新增 `HttpFiscalReceiptClient implements NationalFiscalReceiptClient`**（`infrastructure/fiscal/`），用 `rhn.billing.fiscal.*` 配置选择：`mock-enabled`（进程内）/ `gateway.enabled`（HTTP）/ 默认拒绝，三者互斥，保持“未接入时明确失败，不伪造成功”。
2. 四个方法一一映射到 §2：`issue/query/voidReceipt/redFlush` → `POST {base-url}/v1/receipts/{issue|query|void|redFlush}`；请求体直接序列化 `FiscalModels` 的 `FiscalIssueRequest / (receiptRequestNo,externalReceiptNo,correlationId) / FiscalVoidRequest / FiscalRedFlushRequest`；响应反序列化回 `FiscalIssueResponse / FiscalVoidResponse / FiscalRedFlushResponse`（`Instant` 用 ISO-8601；`query` 的三个参数要包装成 JSON 对象）。`query` 没有请求 record，客户端自拟 `{receiptRequestNo, externalReceiptNo, correlationId}`。
3. 配置项：`base-url`（`http://localhost:18090/fiscal`）、`X-Client-Id/X-Client-Secret`、连接 / 读超时（建议读超时 < 31 秒才能触发 `TEST-TIMEOUT`）。
4. **异常归一化**（`ReceiptApplicationService` 已有“渠道不确定”处理，客户端只需抛出异常 / 返回 PENDING）：超时、HTTP 5xx、非 JSON、`outcome` 与 `success` 矛盾——不要推定失败，维持待定，由 `query` 恢复；`PENDING` 必须带 `externalReceiptNo` 以便后续按它查询。
5. **幂等**：请求必须带稳定的 `idempotencyKey` 与 `receiptRequestNo`（重试同值）；作废 / 冲红同理。mock 对冲红同键重放返回同一红票。
6. **新增回调入口控制器**（RHN 当前没有）：接收 §5 报文 → 校验 `X-Fiscal-Timestamp` 时间窗口与 HMAC-SHA256 签名（常量时间比较）→ 映射成 `FiscalReceiptResultDirectory.VerifiedReceiptResult`（`verifyUrl → controlledObjectReference`，`operation/outcome` 转枚举，`issuedAt` 解析为 `Instant`，`sanitizedPayload` 放脱敏后的原文）→ 调 `accept(...)`；签名错返回 4xx 且**不得调用** `accept`；重复消息（同 `externalMessageBusinessId`）应幂等返回成功。回调密钥放配置，不入库。
7. `ProvincialFiscalReceiptAdapter` 的 `ReceiptInstruction → FiscalIssueRequest` 映射现状已含全部字段，无需改；注意 `redFlush` 现把 `originalFiscalCode/Number` 传 null，mock 因此只按 `originalExternalReceiptNo` 定位，真实平台若要求这两项需补充（RHN 侧改动）。
8. 联调：在 RHN 配置里把回调地址告诉 mock（`PUT /mock/config?protocol=fiscal {"callbackUrl":"http://localhost:8080/<RHN 回调路径>"}`），RHN 回调入口的路径 / 鉴权由 RHN 自定，mock 只发 §5 的头与体。

## 8. 简化与未验证

- 协议为自拟，与真实省财政平台（报文格式、签名算法、字段名、异步通知方式）未核对；`verifyUrl` 指向 mock 自己。
- 红冲只支持全额（RHN 请求无金额）；不模拟换开、重打、电子票据文件 / PDF 下载、票据库存与号段。
- 开具金额校验按 RHN 设计文档推断（资金分项之和等于票面或票面减舍入），真实平台口径可能不同。
- 回调为一次性 best-effort 推送，没有平台侧的重试 / 退避；需要重复投递用 `callback` 端点手动触发。
- 没有对 RHN 真实代码跑过端到端（RHN 侧尚无 HTTP 客户端）；测试用 `FiscalTestClient` 按 `FiscalModels` 手工组包。
