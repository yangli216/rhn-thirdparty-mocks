# 支付 Mock（alipay / wxpay）故障与中间状态场景

触发口径（二选一皆可，金额优先）：**金额特殊值**（RHN 里唯一可控入参，单号/付款码由 RHN 生成）或**单号后缀**（curl/测试）；传输类故障用**全局运行时配置** `PUT /mock/config?protocol=alipay|wxpay`，也可用单号后缀逐单触发。

## 支付（元；微信为分，同表）
| 金额 | 后缀 | 支付宝 | 微信 | RHN 预期（只读核对 adapter） |
|---|---|---|---|---|
| 0.31 | -PAYING | 10003 + WAIT_BUYER_PAY，仅可手动推进 | result_code=FAIL/USERPAYING，同左 | 非10000/WxPayException → PENDING，轮询 query |
| 0.32 | -PAYING-SUCCESS | 查 `pendingQueries` 次后 TRADE_SUCCESS | → SUCCESS | 查单恢复为成功 |
| 0.33 | -PAYING-CLOSED | → TRADE_CLOSED | → CLOSED | CANCELLED |
| 0.34 | -PAYING-FAILED | → TRADE_CLOSED | → PAYERROR | 微信 FAILED；支付宝 CANCELLED |
| 0.41 / 0.42 | -NOFUNDS / -BADCODE | 40004 ACQ.BUYER_BALANCE_NOT_ENOUGH / ACQ.PAYMENT_AUTH_CODE_INVALID | NOTENOUGH / AUTH_CODE_INVALID | 见下方问题 1 |
| 0.44 | -UNKNOWN | 20000 ACQ.SYSTEM_ERROR，实际已成功 | SYSTEMERROR，已成功 | PENDING，查单成功 |
| 0.45 | -UNKNOWN-NOPAY | 20000，无交易 | BANKERROR，无订单 | PENDING，查单不存在 |
| 0.51 | -FINISHED | TRADE_FINISHED | — | 与 SUCCESS 同 |
| — | -NOTPAY | — | NOTPAY | pending |
重复支付：同单号再发起自动返回 ACQ.TRADE_HAS_SUCCESS / TRADE_HAS_CLOSE（微信 ORDERPAID/ORDERCLOSED/USERPAYING）。

## 退款
金额 0.61 / 后缀 -RF-PROC：处理中（手动推进）；0.62 -RF-PROC-SUCCESS：查 N 次后成功；0.63 -RF-PROC-CLOSED：微信 REFUNDCLOSE（支付宝：退款查询一直无 refund_status）；0.64 -RF-UNKNOWN：应答未知但已退；0.65 -RF-REJECT：余额不足。
通用校验：部分/多次退款累计、超额（ACQ.REFUND_AMT_NOT_EQUAL_TOTAL / REFUND_FEE_INVALID）、同 out_request_no/out_refund_no 同金额幂等、金额不同冲突。微信 `reportRefundState=true` 时 orderquery 报 REFUND（默认关）。

## 传输故障（全局配置字段）
`fault`: NONE / HTTP500 / BADBODY(非JSON/非XML) / SLOW / TIMEOUT / BADSIGN(坏签名) / NOSIGN(缺签名)；`faultOp`: pay|query|refund|refundquery|bill（空=全部）；`faultCount`: 只对接下来 N 次生效（0=一直）；`faultProcessed`: true(默认)=业务已执行仅应答损坏，false=请求未处理；`slowDelayMs`(3000) / `timeoutDelayMs`(20000，大于 RHN 15s 读超时)；`pendingQueries`(2)。单号后缀 -HTTP500 等同样触发。请求坏签名沿用 `requireRequestSign`。RHN 预期：HTTP500/BADBODY/超时/坏签/微信缺签 → uncertain（PENDING，勿重复收款）。

## 管理端点
`GET /mock/{alipay/trades|wxpay/orders}[/{no}]`、`GET .../refunds`、`POST .../{no}/advance` body `{"to":"SUCCESS|CLOSED|FINISHED"}`（微信 SUCCESS|CLOSED|REVOKED|PAYERROR|…；退款 SUCCESS|FAILED(支付宝)/REFUNDCLOSE|CHANGE(微信)）、`POST /mock/alipay/refunds/{no}/advance`、`DELETE .../trades|orders` 清订单、`POST /mock/reset` 全量复位。ExchangeLog 记录，故障应答前缀 `[fault=X]`。

## RHN 状态机对照发现的问题
1. 支付宝/微信 initiate 对任何非成功应答（含余额不足、条码无效等确定性失败）一律 PENDING，从不 FAILED；随后查单得“交易不存在”也是 PENDING（支付宝）/异常→uncertain（微信），订单会一直卡在待确认。
2. 支付宝退款恒 PENDING，退款被拒（如商户余额不足）后 refundquery 无 REFUND_SUCCESS，永远 PENDING，无失败出口。

## 未验证 / 简化
- 已在 pom 加 test 依赖 alipay-sdk-java 4.40.1036.ALL、weixin-java-pay 4.8.0（本地 .m2 可解析），但**尚未写真实 SDK 对坏签名/缺签名/超时的验证测试**；验证方法：用 DefaultAlipayClient / WxPayServiceImpl 指向 mock，设 `fault=BADSIGN|NOSIGN|TIMEOUT`，读超时设 1s、timeoutDelayMs 设 2500，断言抛 AlipayApiException / WxPayException。
- 尚未写传输故障（fault）的自动化测试（场景测试 alipay 18 个、wxpay 16 个已通过）。
- 微信/支付宝错误码来自记忆，RHN 不读具体码；金额/手续费简化；自动落地按查询次数，不依赖时钟。
