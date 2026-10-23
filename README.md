# rhn-thirdparty-mocks

RHN 系统各类三方接口的统一 Mock 服务：单进程、内存态、重启即清空。各协议按路径分区，共用管理端点。

| 协议 | id | 业务端点 | 说明 |
|---|---|---|---|
| 医保统一接口 | `chs` | `POST /hsa/api` | HTTP/JSON，按证件号选测试人员与故障场景（`TEST-*`）；含 2202 挂号撤销、`diagnosisRequired` 开关（默认 false）、`mock.chs.cert-aliases` 身份证号→TEST-* 别名映射（预置 RHN 演示患者 12 条），补充见 [docs/chs-mock-protocol.md](docs/chs-mock-protocol.md) |
| 支付宝网关 | `alipay` | `POST /alipay/gateway.do` | RSA2 加签验签；含对账单下载链接与可构造差异的账单，见 [docs/payment-reconciliation-mock.md](docs/payment-reconciliation-mock.md) |
| 微信支付 v2 | `wxpay` | `POST /wxpay/**` | XML / MD5；含 `pay/downloadbill` 对账单 |
| **CA 厂商** | `ca` | `POST /ca/v1/<组>/<操作>` | HTTP/JSON，SM2/SM3 真实可验的测试 CA；协议见 [docs/ca-mock-protocol.md](docs/ca-mock-protocol.md) |
| **财政电子票据** | `fiscal` | `POST /fiscal/v1/receipts/{issue\|query\|void\|redFlush}` | 自拟 HTTP/JSON，字段对齐 RHN `FiscalModels`；异步结果带签名回调；协议与 RHN 适配点清单见 [docs/fiscal-mock-protocol.md](docs/fiscal-mock-protocol.md) |

## 启动

需要 JDK 21、Maven。

```bash
mvn spring-boot:run
# 或先打包再运行（注意：重新 package 会覆盖正在运行的 jar，已有进程在跑时请换个输出位置或先停掉）
mvn -DskipTests package && java -jar target/rhn-thirdparty-mocks-1.0.0.jar
```

- 默认监听 `127.0.0.1:18090`（`server.port` / `server.address`），**所有协议（含 CA）共用这一个端口**。已知本机还占用 8080、5173、18090（本服务的验收实例）、18080 等，另起实例请换端口：`--server.port=18095`。
- CA 协议随包带固定的测试根证书 / 时间戳密钥，**升级到含 CA 的版本需要重启正在运行的实例**才会注册 `/ca/**`；`GET /mock/health` 的 `protocols` 含 `ca` 即已生效。
- 配置见 [application.yml](src/main/resources/application.yml)（`mock.chs.* / mock.alipay.* / mock.wxpay.* / mock.ca.* / mock.fiscal.*`）。
- 新增 `fiscal` 协议与对账单接口同样**需要重启正在运行的实例**才生效（`GET /mock/health` 的 `protocols` 含 `fiscal`）；本服务不会替你重启 18090 上的旧 jar。

## 管理端点（所有协议通用）

| 端点 | 作用 |
|---|---|
| `POST /mock/reset` | 清空全部协议内存态并复位运行时配置 |
| `GET /mock/requests?protocol=<id>&op=<操作>&limit=` | 已收报文（环形缓冲，`mock.requests-buffer-size`）；`infno=` 是医保兼容写法 |
| `GET /mock/config[?protocol=<id>\|all]` / `PUT` | 查看 / 部分更新运行时配置（默认 `chs`） |
| `GET /mock/health` | 存活与已登记协议 |

## CA Mock 快速上手

```bash
# 1. 备案一个测试医生（identityNumber 选场景，TEST-* 见协议文档 §5；其他值为正常人员）
curl -s localhost:18090/ca/v1/signers/enroll \
  -H 'X-Client-Id: mock-ca-client' -H 'X-Client-Secret: mock-ca-secret' -H 'Content-Type: application/json' \
  -d '{"userId":7,"name":"张医生","identityNumber":"110101199001010011"}'

# 2. 导出测试 CA 根证书（配进 RHN 作信任锚）与该医生的证书 / 私钥
curl -s localhost:18090/mock/ca/root
curl -s localhost:18090/mock/ca/signers/mock-7/material

# 3. 免手工确认的联调模式：待签任务被查询时自动签名、扫码登录被轮询时自动确认
curl -s -X PUT 'localhost:18090/mock/config?protocol=ca' -H 'Content-Type: application/json' \
  -d '{"autoConfirmOrders":true,"autoConfirmLoginSigner":"mock-7"}'
```

要点：

- 全部使用 SM2/SM3 与 X.509，证书由固定根签发；RHN 的 `PersonalSignatureVerifier` / `DefaultCaLoginService` 本地验签可直接通过。
- 有状态：登录挑战一次性（重放被拒）、证书吊销 / 过期、签名任务（推送 / 扫码认领 / 自动签、拒签、过期、取消、作废）、`GET /mock/ca/orders` 查签名流水。
- 故障注入：证书过期、已吊销、挑战过期 / 重放、签名失败、验签不通过、超时、HTTP 500、非 JSON、慢响应（签名人场景 + `PUT /mock/config?protocol=ca` 全局开关）。
- **RHN 侧目前没有调用厂商 HTTP 的适配器**，需新增一个 `CaVendorAdapter`；对接配置示例、客户端适配点清单、与真实厂商不一致 / 简化之处都在 [docs/ca-mock-protocol.md](docs/ca-mock-protocol.md) §8–§10。

## 电子票据与对账单快速上手

```bash
# 开具（payerName=TEST-PENDING 走异步：先返回处理中，之后查询 / 手动完成 / 回调变成功）
curl -s localhost:18090/fiscal/v1/receipts/issue -H 'X-Client-Id: mock-fiscal-client' -H 'X-Client-Secret: mock-fiscal-secret' \
  -H 'Content-Type: application/json' -d '{"receiptRequestNo":"R1","idempotencyKey":"K1","payerName":"TEST-PENDING","totalAmount":100.00,"items":[{"lineNo":1,"amount":100.00}]}'
curl -s -X POST localhost:18090/mock/fiscal/receipts/R1/complete        # 手动完成并（已配 callbackUrl 时）推送签名回调
curl -s localhost:18090/mock/fiscal/receipts                           # 查看票据

# 对账单差异：让支付宝账单漏掉 ORD-1、把 ORD-2 金额改成 21.00
curl -s -X PUT localhost:18090/mock/alipay/bill/anomalies -H 'Content-Type: application/json' -d '{"omit":["ORD-1"],"amount":{"ORD-2":"21.00"}}'
```

## 测试

```bash
mvn test
```

全部协议的契约 / 场景 / 管理端点测试都以 `@SpringBootTest(RANDOM_PORT)` 起整套服务，不占用固定端口。医保补充 `ChsRegistrationSettlementTest`；电子票据 `src/test/.../protocol/fiscal/`（契约、场景与故障、回调）；对账单 `AlipayBillTest` / `WxpayBillTest`。CA 测试位于 `src/test/java/com/rhn/mock/protocol/ca/`：协议契约、扫码 / 证书登录、签名任务、故障注入、管理端点，以及一个端到端用例（`CaEndToEndAcceptanceTest`：备案 → 证书登录挑战 → 扫码登录 → 签名 → RHN 侧本地验签 → 吊销）。

## 目录约定

```
core/                 通用：ExchangeLog（已收报文）、MockAdminController、MockProtocol SPI
protocol/<id>/        每个协议一个包：<Id>Protocol（插件）、config/（启动 + 运行时配置）、web/（控制器）、引擎 / 状态
src/main/resources/mock/<id>/   该协议的一次性 throwaway 密钥 / 证书（非真实材料）
src/test/.../protocol/<id>/     测试，support/ 下是按真实调用方报文形状手写的测试客户端
docs/                 协议文档
```

新增协议：实现 `MockProtocol`（`@Component @Order(n)`）、在 `MockApplication` 登记 `*MockProperties`、业务控制器用 `${mock.<id>.…}` 配路径、报文写入 `ExchangeLog`，即自动出现在 reset / requests / config / health。
