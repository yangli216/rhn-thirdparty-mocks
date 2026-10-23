# 支付对账（alipay / wxpay）Mock 说明

## 1. 结论：RHN 对账要下载渠道账单，已在 alipay / wxpay 补齐

只读核对 RHN commit `ceb4ae4d`（三方支付交易对账）：`ReconciliationAdapter.download/parse` 的两个实现都走渠道账单文件——

| 渠道 | RHN 实际调用 | 解析 |
|---|---|---|
| 支付宝 | SDK `alipay.data.dataservice.bill.downloadurl.query`（`bill_type=trade`、`bill_date=yyyy-MM-dd`、`secure=true`）→ 取 `bill_download_url` → `StatementHttpDownload.alipay` 下载 zip | `AlipayStatementParser`：GB18030 中文“业务明细”CSV，`#起始日期` 元数据 + `#交易合计/#退款合计` 汇总（或汇总文件） |
| 微信 | WxJava `downloadRawBill`（`pay/downloadbill`，`bill_type=ALL`，`bill_date=yyyyMMdd`） | `WechatStatementParser`：UTF-8 CSV，每格反引号前缀，明细后接“总交易单数…”汇总表头与汇总行 |

对账逐笔匹配 / 差异核查在 RHN 内部完成；mock 只需提供“账单”并能构造差异。原先 mock 只有付款码支付/查询/退款，现已补齐：

- 支付宝：`alipay.data.dataservice.bill.downloadurl.query`（签名应答，与其他 method 同一网关）+ 文件下载 `GET /alipay/bill/download/{yyyy-MM-dd}`（zip，内含一份 GB18030 CSV）。
- 微信：`POST /wxpay/pay/downloadbill`（成功返回 CSV 文本；失败返回 XML：`return_code=FAIL, return_msg, error_code`）。
- 账单内容 = mock 里**当日（本机时区）已成功的支付 / 退款** + 差异配置；账单汇总按最终明细重算，所以差异数据仍能通过 RHN 解析层，由对账层判出差异。

## 2. 构造差异：`/mock/{alipay|wxpay}/bill/anomalies`

`GET` 查看、`PUT` 部分更新（出现的字段整体替换）、`DELETE` 清空；`POST /mock/reset` 也清空。预览某日最终账单：`GET /mock/{alipay|wxpay}/bill/{yyyy-MM-dd}`（行 + CSV 原文）。

| 字段 | 构造的差异 |
|---|---|
| `omit: ["outTradeNo"]` | 账单漏掉该笔支付 → **本地单边**（RHN 有、渠道无） |
| `omitRefund: ["outRefundNo"]` | 账单漏掉该笔退款 → 本地单边（退款） |
| `extra: [{type,outTradeNo,tradeNo,amount,time,outRefundNo?,refundId?,refundStatus?}]` | 账单多出 RHN 没有的支付 / 退款 → **渠道单边**；给已有支付补一条 `REFUND` 即“渠道已退、本地未退”**状态差异**；`time` 决定落在哪个账单日 |
| `amount: {"outTradeNo或outRefundNo":"21.00"}` | **金额差异** |
| `refundStatus: {"outRefundNo":"PROCESSING"}` | **状态差异**（仅微信：`PROCESSING / FAIL / CHANGE`；支付宝账单无退款状态列，用 omitRefund / extra 表达） |
| `duplicate: ["outTradeNo或outRefundNo"]` | 同一笔出现两次 → **重复交易** |
| `fetch` | 下载阶段结果：`AVAILABLE`(默认) / `NOT_READY` / `NOT_FOUND` / `NO_DATA` / `AUTH_FAILED` / `RATE_LIMITED` / `UNSUPPORTED`，以及 `CORRUPT`（汇总笔数故意多 1，演练解析失败） |

`fetch` 与 RHN 状态映射：支付宝 sub_code `BILL_NOT_READY→NOT_READY(默认分支)`、`BILL_NOT_EXIST→NOT_FOUND`、`NO_BILL_DATA→NO_DATA_CONFIRMED`（应答带签名）、`isv.insufficient-isv-permissions→AUTH_FAILED`、`USER_RATE_LIMIT→RATE_LIMITED`、`TYPE_NOT_SUPPORTED→UNSUPPORTED`；微信 error_code `20003→NOT_READY`、`20002→NOT_FOUND`、`SIGNERROR→AUTH_FAILED`、`20008→RATE_LIMITED`。账单日晚于今天一律“未就绪”；当日无交易：支付宝 `NO_BILL_DATA`，微信返回只有汇总行（总交易单数 0）的空账单。

示例（先走一笔支付，再让账单漏单 + 改金额 + 多一笔渠道单）：

```bash
curl -s -X PUT localhost:18090/mock/alipay/bill/anomalies -H 'Content-Type: application/json' \
  -d '{"omit":["ORD-1"],"amount":{"ORD-2":"21.00"},"extra":[{"type":"PAYMENT","outTradeNo":"CH-ONLY","tradeNo":"CH-1","amount":"7.70"}]}'
```

## 3. RHN 侧对接要点（mock 无法替代 RHN 的改动）

1. **账单下载地址被 RHN 白名单拒绝**：`StatementHttpDownload.alipay` 只允许 `https://dwbillcenter(-dl).alipay.com:443`，且拒绝回环 / 内网解析地址。mock 的下载链接是 `http://127.0.0.1:<port>/alipay/bill/download/<date>`，**RHN 必须像网关地址一样加一个仅测试开启的放行项**（例如挂在现有 `rhn.billing.payment.mock` 开关下），否则支付宝账单下载一定失败（微信走 WxJava 的 apiHostUrl，沿用已有覆盖即可，需确认 `downloadRawBill` 也走覆盖后的基址）。
2. 支付宝商户身份：mock 账单不带“商户PID”列（解析器对该列可选，给了才比对）；账单表头日期证据用 `#起始日期：[yyyy-MM-dd 00:00:00]`。
3. 微信账单行里的 `商户号 / 公众账号ID` 回显请求中的 `mch_id / appid`，与 RHN 商户配置一致即可通过 `same(...)` 校验。

## 4. 格式假设与未验证项

- **支付宝退款列名**：mock 用 `退款批次号`（RHN 解析器 `required(columns, cells, "退款批次号", "商户退款请求号", "商户退款单号")` 精确匹配）。我印象里真实业务明细的列名是 `退款批次号/请求号`，若属实，RHN 解析器遇到真实账单会因缺列失败——**需拿真实 / 沙箱账单核验**，mock 不能证明。
- 微信 `downloadbill` 失败应答的 `error_code` 字段如何被 WxJava 解析进 `WxPayException.getErrCode()` 未用真实 SDK 验证（RHN 取 `errCode` 缺失时回退 `returnMsg`）。
- 金额、手续费简化：手续费恒 0.00，微信应结金额 = 订单金额，无代金券 / 充值券。
- 未在真实 RHN 对账链路上端到端跑过（RHN 只读、且账单下载被白名单挡住）；本侧只用测试按解析器的约束（列数、反引号、汇总一致、GBK zip）自检，不是对 RHN 解析器的真实调用。

## 5. 现有支付契约测试对 RHN 实际调用与异常的覆盖（只读核对，未改动）

| RHN 用到的调用 / 分支 | mock 覆盖 |
|---|---|
| alipay `trade.pay`（10000 成功 / 非 10000→待定） | ✔ 成功、`SIM_FAIL_`、`SIM_PAYING_` |
| alipay `trade.query`：`TRADE_SUCCESS` / `TRADE_FINISHED` / `TRADE_CLOSED` / 其他→待定 | 仅 SUCCESS、CLOSED；**缺 `WAIT_BUYER_PAY`、`TRADE_FINISHED`** |
| alipay `trade.refund` + `fastpay.refund.query`（RHN 退款一律先 pending 再查询 `REFUND_SUCCESS`） | ✔ 仅成功；**缺退款处理中 / 非 REFUND_SUCCESS** |
| wechat `micropay` / `orderquery`（SUCCESS、CLOSED、REVOKED、PAYERROR） | ✔（单号后缀钩子）；**缺 `USERPAYING` / `NOTPAY` / `REFUND` 状态、micropay 的 `USERPAYING` 业务失败** |
| wechat `refundv2` / `refundquery`（SUCCESS、REFUNDCLOSE、其他→待定） | 仅 SUCCESS；**缺 `PROCESSING` / `REFUNDCLOSE`** |
| 传输超时（RHN 读超时 15s，→“渠道不确定”） | **缺**：alipay / wxpay 没有延迟 / 超时注入 |
| HTTP 500 / 非 JSON·非 XML 应答（RHN 一律 `uncertain()`） | **缺** |
| 响应验签失败（alipay SDK 验签；微信 `identity()` 要求响应带 sign 且 appid/mch_id 与商户一致、无 sub_mch_id） | **缺**：mock 只会产出正确签名；测试只覆盖“请求坏签名被拒” |
| 请求坏签名 | ✔ |

建议（未实现，另行安排）：给 alipay / wxpay 补与 CA、fiscal 一致的全局故障注入（HTTP500 / BADJSON / SLOW / TIMEOUT / 响应坏签名 / 无 sign），并补上表的状态钩子。
