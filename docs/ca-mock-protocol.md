# CA 厂商 Mock 接口协议

适用对象：RHN 医院信息系统 `backend/rhn-platform` 的 `com.rhn.platform.cryptography` 包（feat/ca-foundation 合入后的「CA 可选签名」与「CA 登录」）。
本协议由 `rhn-thirdparty-mocks` 的 `ca` 协议插件实现（包 `com.rhn.mock.protocol.ca`），与医保 / 支付宝 / 微信 Mock 共用同一进程、同一套管理端点。

## 0. 协议从何而来（重要）

RHN 代码里**只定义了厂商适配 SPI，没有任何真实厂商的 HTTP 协议**：

- `CaVendorAdapter` 及其端口 `CaVerificationPort / CaTimestampPort / CaSignerEnrollmentPort / CaRemoteSigningPort / CaMobileLoginPort / CaSigningGrantPort / CaCallbackTranslator`；
- 唯一的实现是进程内的 `DevelopmentCaAdapter`（`development-ca`，`DEVELOPMENT_ONLY`）；
- 设计稿 `docs/architecture/通用CA集成功能设计.md` §7 只给出医网信（BJCA）接口与 SPI 方法的对照（`synDoctor`、`oauth/authorize`、`synRecipeInfo` 等），**没有字段级报文**；医网信适配器 `bjca-ywx` 属于 1c，尚未实现。

因此本协议是**按 SPI 语义设计的一套 HTTP/JSON 协议**：每个 SPI 方法对应一个 `POST` 操作，请求 / 响应字段与 SPI 的 record 一一对应（字段名同名），状态枚举沿用 SPI 的枚举，签名与证书口径沿用 RHN 的 `SmCrypto` / `PersonalSignatureVerifier`。它不是任何真实厂商的报文，RHN 侧需要写一个调用它的适配器（见 §9 清单）。

## 1. 通用约定

| 项 | 约定 |
|---|---|
| 端点 | `POST {base}/v1/<组>/<操作>`，`base` 默认 `/ca`（`mock.ca.base-path`）；同一进程默认端口 `18090` |
| 编码 | 请求 / 响应均为 `application/json;charset=UTF-8`；空请求体视为 `{}` |
| 凭据 | 请求头 `X-Client-Id`、`X-Client-Secret`（对应设计稿的 `clientId/clientSecret`），默认 `mock-ca-client` / `mock-ca-secret`；`client-secret` 置空关闭校验。缺失或不符 → HTTP 401 |
| 二进制 | 证书 = Base64(DER)；签名值 / 摘要 / 令牌载荷 = 标准 Base64；时间 = ISO-8601 UTC（`Instant.toString()`） |
| 成功 | HTTP 200，`{"code":0,"message":"OK","requestId":"<uuid>","data":{…}}` |
| 业务失败 | HTTP 200，`{"code":-1,"errorCode":"CA_XXX","message":"…","requestId":"…","data":null}` |
| 协议级错误 | 凭据错 401；请求体非 JSON / 非对象 400；未知操作 404；故障注入 500 / 非 JSON，见 §6 |
| 幂等 | `signing/submit` 以 `signingOrderId` 幂等（SPI 要求）；`signers/enroll` 以 `userId` 幂等 |
| 超时与重试 | RHN 侧没有写死的厂商超时 / HTTP 重试；设计稿只规定：外部调用在事务外，提交失败按退避重试（`poll-interval` 10s 起翻倍，封顶 `max-poll-interval` 5min），超 `max-submit-attempts`（8）置 FAILED，查询失败只延后下次检查。Mock 不改变这些语义，只提供 SLOW / TIMEOUT / HTTP500 / BADJSON 让适配器验证自己的超时与重试 |

## 2. 密码学口径（与 RHN 一致，均经测试验证）

- 全部使用 **SM2 / SM3**（BouncyCastle `bcprov-jdk18on` / `bcpkix-jdk18on` 1.82，与 RHN 同版本，未注册的 Provider 实例）。RHN 代码里没有 RSA 签名路径，Mock 不提供 RSA。
- 签名 = `SM3withSM2`，DER 编码，缺省 userId `1234567812345678`（GB/T 32918；已用 OpenSSL `dgst -sm3 -sigopt distid:1234567812345678` 独立验过根证书对终端证书的签名）。
- 证书指纹 = DER 的 SM3，大写十六进制（RHN `SmCrypto.fingerprint`，用作签名人绑定键）。
- 证书链：测试 CA 根 → 终端证书（二级，`pathLen` 无需更多层）。根证书 `CN=RHN Mock CA Root,O=RHN MOCK ONLY`，`keyCertSign|cRLSign`；终端证书 `digitalSignature|nonRepudiation`（`SmCrypto.allowsSigning` 通过）。
- **根证书与时间戳证书 / 私钥固定随包发布**（`src/main/resources/mock/ca/`，throwaway 材料），因此 RHN 侧配置一次信任锚后重启 Mock 不必重配；**终端证书与私钥每次备案现签、仅存内存，重启即丢**（见 §10）。
- 签名对象：
  - **远程签名（SM2_ENVELOPE）**：厂商只收到声明摘要，签名对象是 UTF-8 封装 JSON `{"vendor":"mock-ca","order":"<externalOrderId>","digestAlgorithm":"SM3","digest":"<Base64 摘要>"}`（字段顺序固定）。RHN 的 `envelopeBinds` 需解析该 JSON 验证 `vendor`、`digestAlgorithm`、`digest`，再用证书公钥对封装原文验签。
  - **UKey 本地签名（SM2_STATEMENT）**：中间件对浏览器要求签的字节直接 SM3withSM2；Mock 用 `/mock/ca/signers/{id}/client-sign` 模拟。
- 时间戳令牌：`Base64(payloadJson) + "." + Base64(SM2 签名)`，payload = `{"authority":"mock-ca-tsa","time":"<ISO>","digest":"<Base64 SM3(被盖章签名值)>"}`，由时间戳证书私钥签名；`authority` 固定为 `mock-ca-tsa`。RHN `verifyTimestamp` 用 `trust/anchors` 返回的 `timestampCertificate` 公钥验签并核对 digest（格式与 `DevelopmentCaAdapter` 一致）。

## 3. 错误码

| errorCode | 含义 |
|---|---|
| `CA_INVALID_ARGUMENT` | 缺参、格式 / 长度非法、未知 mode / format / 摘要算法 |
| `CA_SIGNER_NOT_FOUND` / `CA_SIGNER_NOT_READY` / `CA_SIGNER_STATE_CONFLICT` | 签名人不存在 / 状态不是 READY / 状态不允许该动作 |
| `CA_CERTIFICATE_REVOKED` | 签名人证书已吊销（提交被拒，或待签任务在签名时 FAILED） |
| `CA_ORDER_NOT_FOUND` / `CA_ORDER_NOT_SIGNED` / `CA_ORDER_NOT_CANCELLABLE` / `CA_ORDER_STATE_CONFLICT` | 任务不存在 / 未签名不可取结果 / 仅待签可取消 / 状态冲突 |
| `CA_MODE_UNSUPPORTED` | `CLIENT_TOKEN` 在本地完成，不走远程签名接口 |
| `CA_SIGN_FAILED` | 厂商侧签名失败（任务 FAILED 的 `errorCode`） |
| `CA_BATCH_LIMIT_EXCEEDED` | 批量查询超过 100 条 |
| `CA_GRANT_NOT_PENDING` / `CA_LOGIN_NOT_PENDING` / `CA_LOGIN_NOT_FOUND` / `CA_CHALLENGE_NOT_FOUND` | 管理端模拟动作的前置状态不满足 |

验签类操作（`login/certificate/verify`、`trust/verify-signature`）的「不通过」不是业务失败，而是 `data.valid=false` + `data.reasonCode`：`CA_CERTIFICATE_UNTRUSTED`、`CA_CERTIFICATE_EXPIRED`、`CA_CERTIFICATE_USAGE_INVALID`、`CA_CERTIFICATE_REVOKED`、`CA_SIGNATURE_INVALID`、`CA_SIGNATURE_MALFORMED`、`CA_CHALLENGE_NOT_FOUND`、`CA_CHALLENGE_REPLAYED`、`CA_CHALLENGE_EXPIRED`（码名与 RHN `DefaultCaLoginService` / `PersonalSignatureVerifier` 的失败码一致，便于直接透传）。

## 4. 操作清单

下表 SPI 列指 RHN 的对应方法。除另有说明，请求体为 JSON 对象，响应字段位于 `data`。

| 操作 | SPI | 说明 |
|---|---|---|
| `signers/enroll` | `CaSignerEnrollmentPort.enroll` | 备案签名人 |
| `signers/query` | `CaSignerEnrollmentPort.query` | 查询状态与证书 |
| `login/mobile/start` | `CaMobileLoginPort.start` | 发起扫码登录 |
| `login/mobile/poll` | `CaMobileLoginPort.poll` | 轮询扫码结果 |
| `login/certificate/challenge` | （RHN 现为本地生成） | 取证书登录挑战（可选的厂商侧实现） |
| `login/certificate/verify` | （RHN 现为本地验签） | 厂商侧验签（交叉核对用） |
| `signing/submit` | `CaRemoteSigningPort.submit` | 提交签名任务 |
| `signing/query` | `CaRemoteSigningPort.query` | 查询任务 |
| `signing/query-batch` | `CaRemoteSigningPort.queryBatch` | 批量查询（≤100） |
| `signing/fetch` | `CaRemoteSigningPort.fetch` | 取签名结果 |
| `signing/cancel` | `CaRemoteSigningPort.cancel` | 取消待签任务 |
| `grants/request` / `grants/query` / `grants/revoke` | `CaSigningGrantPort.request/query/revoke` | 自动签授权 |
| `timestamp/stamp` | `CaTimestampPort.stamp` | 给签名值打时间戳 |
| `timestamp/verify` | `CaVerificationPort.verifyTimestamp` | 校验时间戳（RHN 以本地验证为准） |
| `trust/anchors` | `CaVerificationPort.trustAnchors` | 信任根 + 时间戳证书 |
| `trust/envelope-binds` | `CaVerificationPort.envelopeBinds` | 封装是否内嵌该摘要 |
| `trust/verify-signature` | （设计稿「厂商验签只作交叉核对」） | 厂商验签 |
| `trust/certificate-status` | （RHN 1b 未做 CRL/OCSP） | 证书吊销 / 有效状态 |

`CaCallbackTranslator`（厂商回调）：设计稿定位为「回调只作提示、仍以查询为准」，一期默认只轮询。**Mock 不主动回调**，如需，适配器收到任意通知后调用 `signing/query` 即可；待 1c 确定厂商回调格式再扩展。

### 4.1 `signers/enroll`

请求（对应 `SignerProfile`；`identityNumber` 同时是**场景选择键**，见 §5）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | 是 | 写入证书 CN |
| `identityNumber` | 是 | 证件号；仅用于选场景，Mock 不存储明文 |
| `userId` | 否 | 有则 `externalSignerId = "mock-" + userId` 且幂等；无则顺序号 |
| `practitionerId`、`identityType`、`mobile`、`department` | 否 | 记录 / 忽略；手机号只留末 4 位 |

响应（对应 `Enrollment`）：`externalSignerId`、`state`（`PENDING_REVIEW / REJECTED / CERTIFICATE_PENDING / READY / DISABLED`）、`certificate`（Base64 DER，READY 才有，否则 null）、`certificateNotAfter`、`message`。

### 4.2 `signers/query`

请求 `{"externalSignerId":"mock-7"}`；响应同 enroll。证书被吊销的签名人 `state=DISABLED`，`message="证书已吊销：<原因>"`。

### 4.3 扫码登录

`login/mobile/start` 请求 `{"enableAutoSign":false,"autoSignHours":0}`（可空）→ 响应 `{"externalRequestId","qrContent":"mock-ca://login/<id>","expiresAt"}`，有效期 `loginChallengeTtlSeconds`（默认 120 秒，与 RHN `CHALLENGE_TTL` 一致）。

`login/mobile/poll` 请求 `{"externalRequestId"}` → 响应（对应 `LoginResult`）：`state`（`PENDING / CONFIRMED / REJECTED / EXPIRED`）、`externalSignerId`、`certificate`（CONFIRMED 才有）、`autoSignGranted`（发起时 `enableAutoSign=true` 且确认后为 true）、`message`。

- 终态只能被领取一次：CONFIRMED / REJECTED 读过一次后，再 poll 返回 `EXPIRED`（"不存在或已被领取"）；未知 id 同样 `EXPIRED`。
- 医生手机确认由管理端 `/mock/ca/logins/{id}/confirm` 模拟，或配置 `autoConfirmLoginSigner` 让 poll 自动确认。
- 确认时签名人证书已吊销 / 状态非 READY → `REJECTED`，`message` 说明原因。

### 4.4 证书登录挑战（厂商侧可选实现）

RHN 现在**本地**生成挑战（`RHN-CA-LOGIN-1…`）并本地验签，不调厂商。Mock 仍提供一对厂商侧接口，便于想把挑战交给厂商的适配器、或做交叉核对：

- `login/certificate/challenge` → `{"challengeId","toBeSigned"(Base64 文本),"expiresAt"}`；`toBeSigned` 形如 `MOCK-CA-LOGIN-1\nchallenge:<id>\nnonce:<随机>\nexpires:<ISO>`。
- `login/certificate/verify` 请求 `{"challengeId","signatureValue","certificate"}` → `{"valid","reasonCode","message","externalSignerId"}`。挑战**一次性**：无论成败都被消耗；再次提交 → `CA_CHALLENGE_REPLAYED`；超时 → `CA_CHALLENGE_EXPIRED`；未知 → `CA_CHALLENGE_NOT_FOUND`。校验顺序：证书链 → 有效期 → 密钥用法 → 吊销 → SM2 验签（顺序与 RHN 一致，吊销为 Mock 额外增加）。

### 4.5 签名任务

`signing/submit` 请求（对应 `RemoteSigningRequest`）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `signingOrderId` | 是 | RHN 任务号，**幂等键** |
| `mode` | 是 | `PUSH`（推送到手机）/ `QR_CLAIM`（扫码认领）/ `AUTO`（已授权自动签）；`CLIENT_TOKEN` 返回 `CA_MODE_UNSUPPORTED` |
| `externalSignerId` | PUSH/AUTO 必填 | `QR_CLAIM` 可空（认领时确定签名人） |
| `digestAlgorithm` | 是 | `SM3` 或 `SHA-256` |
| `statementDigest` | 是 | Base64 摘要，须 32 字节；**声明原文不出院** |
| `tenantId`、`organizationId`、`sceneCode`、`subject`、`patientDisplay` | 否 | 记录并在签名流水中可查 |
| `expiresAt` | 否 | 任务过期时间，缺省 7 天（Mock 内固定，对应 RHN 的 `order-ttl` 默认值） |

提交时校验：签名人存在、READY、证书未吊销。响应（`RemoteOrderStatus`）：`externalOrderId`、`state`、`claimContent`（`QR_CLAIM` 待签时为 `mock-ca://claim/<id>`）、`errorCode`、`message`。`AUTO` 且签名人已授权自动签 → 提交即 `SIGNED`。

状态（沿用 `RemoteState`）：`AWAITING_SIGNER → SIGNED`，终态 `REJECTED / EXPIRED / CANCELLED / VOIDED_BY_SIGNER / FAILED`。到期在**被查询时**惰性置 `EXPIRED`。`FAILED` 时 `errorCode` 给出原因（`CA_SIGNER_NOT_READY / CA_CERTIFICATE_REVOKED / CA_SIGN_FAILED`）。

- `signing/query`：`{"externalOrderId"}` → 状态；未知任务 → `CA_ORDER_NOT_FOUND`。
- `signing/query-batch`：`{"externalOrderIds":[…]}`（≤100，否则 `CA_BATCH_LIMIT_EXCEEDED`）→ `{"orders":[状态…]}`；未知项以 `state=FAILED, errorCode=CA_ORDER_NOT_FOUND` 内联返回，不使整批失败。
- `signing/fetch`：仅 `SIGNED`（否则 `CA_ORDER_NOT_SIGNED`）→ `RemoteSignature`：`format`（固定 `SM2_ENVELOPE`）、`signatureValue`、`certificate`、`signedEnvelope`、`signedAt`、`timestampAuthority`、`timestampToken`。
- `signing/cancel`：仅 `AWAITING_SIGNER` 可取消 → `CANCELLED`；已取消重复调用幂等；其他状态 `CA_ORDER_NOT_CANCELLABLE`。

### 4.6 自动签授权

`grants/request` `{"externalSignerId","durationSeconds"}`（缺省 4 小时）→ `{"qrContent":"mock-ca://grant/<id>","expiresAt"(120 秒内需批准)}`，状态 `PENDING`；`grants/query` → `{"state":"NONE|PENDING|GRANTED|REFUSED"}`；`grants/revoke` → `NONE`。批准 / 拒绝由管理端模拟；授权到期自动回到 `NONE`。

### 4.7 时间戳与信任材料

- `timestamp/stamp` `{"signatureValue"}` → `{"authority","token","time"}`。
- `timestamp/verify` `{"authority","token","signatureValue"}` → `{"valid","time"}`。
- `trust/anchors` → `{"certificates":[<根证书>],"timestampAuthority","timestampCertificate","rootSubject","rootFingerprint"}`。
- `trust/envelope-binds` `{"envelope","digestAlgorithm","statementDigest"}` → `{"binds":bool}`。
- `trust/verify-signature` `{"format":"SM2_STATEMENT|SM2_ENVELOPE","signatureValue","certificate","statement"(Base64 原文)|"statementDigest","signedEnvelope"(ENVELOPE),"digestAlgorithm"(ENVELOPE),"signedAt"(可选，证书有效期按此时刻判断)}` → `{"valid","reasonCode","message","externalSignerId"}`。
- `trust/certificate-status` `{"certificate"}` → `{"status":"GOOD|REVOKED|EXPIRED|UNTRUSTED|UNKNOWN","fingerprint","notAfter","revokedAt","revokeReason"}`（`UNKNOWN` = 链可信但非本 Mock 签发的人员）。

## 5. 测试人员 / 证书场景

备案时 `identityNumber`（不区分大小写）选场景，与医保 Mock 按证件号选场景同一套路；未匹配一律 `NORMAL`。管理端 `POST /mock/ca/signers` 可用 `scenario`（下表「场景名」）直接签发。

| identityNumber | 场景名 | 行为 |
|---|---|---|
| 其他任意值 | `NORMAL` | 备案即 READY，证书有效 1 年 |
| `TEST-REVIEW` | `PENDING_REVIEW` | 停在 PENDING_REVIEW 无证书；`/mock/ca/signers/{id}/approve` 后 READY |
| `TEST-REJECT` | `REJECTED` | 备案被拒，无证书 |
| `TEST-CERT-PENDING` | `CERT_PENDING` | CERTIFICATE_PENDING，approve 后 READY |
| `TEST-DISABLED` | `DISABLED` | 账号停用，无证书 |
| `TEST-CERT-EXPIRED` | `CERT_EXPIRED` | READY 但证书已过期（notAfter 为昨天）；仍会签名 / 登录，**RHN 本地验签应判 `CA_CERTIFICATE_EXPIRED`** |
| `TEST-REVOKED` | `REVOKED` | 备案返回 READY+证书（便于绑定），但证书已吊销：之后 `signers/query` 为 DISABLED，提交被拒 `CA_CERTIFICATE_REVOKED`，扫码登录 REJECTED，证书登录验签 `CA_CERTIFICATE_REVOKED`，`certificate-status=REVOKED`。已有签名人也可 `/mock/ca/signers/{id}/revoke` 吊销 |
| `TEST-SIGN-FAIL` | `SIGN_FAIL` | 任务签名时 FAILED（`CA_SIGN_FAILED`） |
| `TEST-SIGN-REJECT` | `SIGN_REJECT` | 医生拒签（任务 REJECTED） |
| `TEST-BAD-SIGNATURE` | `BAD_SIGNATURE` | 任务 SIGNED，但签名值被破坏，**RHN 本地验签应判 `CA_SIGNATURE_INVALID`** |
| `TEST-BAD-ENVELOPE` | `BAD_ENVELOPE` | 任务 SIGNED，封装内嵌摘要与请求摘要不符（`envelopeBinds` 应为 false） |
| `TEST-VERIFY-FAIL` | `VERIFY_FAIL` | 厂商侧 `login/certificate/verify` / `trust/verify-signature` 对其证书一律 `CA_SIGNATURE_INVALID` |
| `TEST-SLOW` | `SLOW` | 涉及该人员的调用延迟 `slowDelayMs`（默认 5s）后正常应答 |
| `TEST-TIMEOUT` | `TIMEOUT` | 延迟 `timeoutDelayMs`（默认 31s，超过常见 30s 客户端超时）后应答 |
| `TEST-HTTP500` | `HTTP500` | 涉及该人员的调用返回 HTTP 500 |
| `TEST-BADJSON` | `BADJSON` | 涉及该人员的调用返回 HTTP 200 + 非 JSON 正文 |

「涉及该人员」= 备案（按 identityNumber）、以及请求里能解析出该签名人的操作（`externalSignerId`、其任务的 `externalOrderId`、已确认登录的 `externalRequestId`）。无法关联到签名人的操作（如 `trust/anchors`、`timestamp/*`、`login/mobile/start`）用全局故障，见 §6。

## 6. 故障注入

运行时配置，经 `PUT /mock/config?protocol=ca` 部分更新（`POST /mock/reset` 复位）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `fault` | `NONE` | `HTTP500`（500+JSON 错误体）/ `BADJSON`（200+`<html>…`）/ `SLOW`（延迟 `slowDelayMs`）/ `TIMEOUT`（延迟 `timeoutDelayMs`），作用于全部业务操作 |
| `faultOp` | 空 | 非空则故障只作用于该操作（如 `signing/submit`） |
| `slowDelayMs` / `timeoutDelayMs` | 5000 / 31000 | 延迟毫秒；自动化测试可改小 |
| `forceDelayMs` | 0 | 所有业务操作先固定延迟 |
| `forceErrorCode` / `forceMessage` | null | 非空则所有业务操作直接以该错误码失败（HTTP 200 + `code=-1`） |
| `clientSecret` | `mock-ca-secret` | 置空关闭凭据校验 |
| `loginChallengeTtlSeconds` | 120 | 扫码登录 / 证书挑战有效期；改成 1 可验证「挑战过期」 |
| `autoConfirmOrders` | false | true：待签任务被查询时自动签名、自动签授权被查询时自动批准（免手工点手机） |
| `autoConfirmLoginSigner` | 空 | 非空：扫码登录被轮询时自动以该签名人确认 |

挑战类故障另有：`/mock/ca/logins/{id}/expire`、`/mock/ca/challenges/{id}/expire`（立即过期）、挑战重放（同一挑战第二次验签自动判重放）。

## 7. 管理与测试辅助端点

通用（`MockAdminController`）：`POST /mock/reset`（含 `ca`）、`GET /mock/requests?protocol=ca[&op=signing/submit][&limit=]`（已收报文，`refId` = 任务号 / 签名人号 / 请求号）、`GET|PUT /mock/config?protocol=ca`、`GET /mock/health`（`protocols` 含 `ca`）。

CA 专有 `/mock/ca/**`（测试人员的遥控器，**不要求** `X-Client-*`，服务默认只监听 127.0.0.1）：

| 端点 | 作用 |
|---|---|
| `GET /mock/ca/root`、`GET /mock/ca/tsa` | 导出测试 CA 根证书 / 时间戳证书（PEM，`text/plain`），配进 RHN 作信任锚 |
| `GET /mock/ca/signers` | 列出签名人（状态、场景、指纹、是否吊销） |
| `POST /mock/ca/signers` `{"name","userId","scenario","mobile"}` | 直接签发测试人员（不经备案） |
| `GET /mock/ca/signers/{id}/material` | 导出证书与私钥：`certificatePem/Base64`、`privateKeyPem`（PKCS#8）/`privateKeyPkcs8Base64`、`fingerprint` |
| `POST /mock/ca/signers/{id}/approve` | PENDING_REVIEW / CERTIFICATE_PENDING → READY 并签发证书 |
| `POST /mock/ca/signers/{id}/revoke` `{"reason"}` | 吊销证书 |
| `POST /mock/ca/signers/{id}/client-sign` `{"data":"<Base64>"}` | 模拟 UKey 中间件本地签名 → `{signatureValue, certificate}`；记入 `GET /mock/ca/client-signs` |
| `GET /mock/ca/orders` | **签名流水**：全部任务的状态、签名人、摘要、场景、主题、时间 |
| `POST /mock/ca/orders/{ref}/confirm` `{"externalSignerId"}` | 模拟医生手机确认 / 认领；`ref` 为外部任务号或 RHN `signingOrderId`；`externalSignerId` 仅 QR_CLAIM 需要 |
| `POST /mock/ca/orders/{ref}/reject`、`/void` | 模拟拒签 / 签后作废 |
| `POST /mock/ca/logins/{id}/confirm` `{"externalSignerId"}`、`/reject`、`/expire` | 模拟扫码确认 / 拒绝 / 让请求过期 |
| `POST /mock/ca/challenges/{id}/expire` | 让证书登录挑战过期 |
| `POST /mock/ca/grants/{signerId}/approve`、`/refuse` | 模拟批准 / 拒绝自动签 |

管理端点失败返回 4xx JSON `{"errorCode","message"}`（404 对象不存在 / 400 参数 / 409 状态冲突）。

## 8. RHN 配置对接

RHN 现状：`rhn.crypto.ca.enabled=true`、`default-vendor=<适配器编码>`、`rhn.security.refresh-login.enabled=true`、`rhn.crypto.enforce-compliance=false`（仅当使用 `DEVELOPMENT_ONLY` 适配器时；见 §10）。设计稿 §10 约定厂商连接参数 `baseUrl / clientId / clientSecret` 按机构放 `rhn.crypto.ca.vendors.<vendor>.organizations[*]`，**该配置类 RHN 尚未实现**，下面是适配器写好后的建议形态：

```yaml
rhn:
  crypto:
    ca:
      enabled: true
      default-vendor: mock-ca          # 新适配器的 vendorCode（小写 kebab-case）
      vendors:
        mock-ca:
          organizations:
            - tenant-id: 1
              base-url: http://127.0.0.1:18090/ca
              client-id: mock-ca-client
              client-secret: mock-ca-secret
```

信任锚：`GET http://127.0.0.1:18090/mock/ca/root` 取根证书（或适配器启动时调 `trust/anchors`），作为 `CaVerificationPort.trustAnchors` 的返回值。

## 9. RHN 侧需新增的客户端适配点（清单，待决策）

协议里 RHN 现无任何 HTTP 实现，要联调需新增一个 `CaVendorAdapter` 实现（建议包 `infrastructure/ca/mock`，仅在联调 profile 注册，`assurance` 取 `DEVELOPMENT_ONLY` 或新增 `INTEGRATION_TEST` 并同步放行规则）：

1. `vendorCode()` = `mock-ca`；`capabilities()`：`signingModes = PUSH, QR_CLAIM, AUTO, CLIENT_TOKEN`，`formats = SM2_ENVELOPE (+SM2_STATEMENT)`，`batchQueryLimit = 100`。
2. `configured(tenantId, orgId)`：读 base-url / clientId / clientSecret 配置。
3. HTTP 客户端：带 `X-Client-Id/X-Client-Secret`，设置连接 / 读超时（建议 ≤30s，才能让 TIMEOUT 场景触发），解析 `code/errorCode`，把 HTTP 5xx、非 JSON、超时统一映射为可重试的外部调用失败。
4. `CaSignerEnrollmentPort` → `signers/enroll`、`signers/query`（`Enrollment.state` 同名枚举）。
5. `CaRemoteSigningPort` → `signing/submit|query|query-batch|fetch|cancel`；`fetch` 的 `format=SM2_ENVELOPE`；`submit` 必须带 `signingOrderId` 保证幂等。
6. `CaMobileLoginPort` → `login/mobile/start|poll`（`LoginResult.State` 同名枚举）。
7. `CaSigningGrantPort` → `grants/*`。
8. `CaTimestampPort.stamp` → `timestamp/stamp`；`CaVerificationPort.verifyTimestamp` → 本地用 `timestampCertificate` 验令牌（或调 `timestamp/verify`）；`envelopeBinds` → 本地解析封装 JSON（§2）；`trustAnchors` → 缓存 `trust/anchors`。
9. 证书登录无需厂商接口（RHN 本地生成挑战与验签即可，UKey 驱动用 `/mock/ca/signers/{id}/client-sign` 替身）。
10. 可选增强：RHN 当前**不做证书吊销检查**（设计稿 §8.2 标注 1b 未做 CRL/OCSP）。若要联调吊销，适配器 / 登录服务需调用 `trust/certificate-status`；否则吊销只会体现在 Mock 侧（提交被拒、扫码登录 REJECTED）。
11. 另外 `DefaultCaLoginService.confirmedMobile` 只比对指纹，不检查证书有效期；`TEST-CERT-EXPIRED` 的扫码登录因此会通过，属 RHN 现状，不是 Mock 缺陷。

## 10. 与真实厂商不一致 / 简化之处

- **协议本身是自拟的**（§0）；真实医网信等厂商的接口路径、字段、认证（OAuth、`clientSecret` 签名等）、状态码（0/10/2/6/7/8/9）、模板 `templateId`、`head.selfSign` 等均未模拟。
- 只有 SM2/SM3，无 RSA；证书链固定二级；无 CRL / OCSP 分发点，吊销状态只通过 `trust/certificate-status` 与各操作的拒绝体现。
- 终端证书私钥在 Mock 内存中，由 Mock 代签（真实厂商的私钥在医生手机 / UKey / 云端）；医生的手机确认、UKey 签名用管理端点模拟。
- 终端证书与私钥重启即丢（根 / 时间戳材料固定）：RHN 绑定的指纹在 Mock 重启后失效，需重新备案 / 重新绑定。
- 时间戳令牌是自定义 JSON+SM2，不是 RFC 3161 TSA 响应。
- 签名封装是自定义 JSON（与 `DevelopmentCaAdapter` 同构），不是 PKCS#7 / 厂商 P1 + 证书结构；没有 `SM2_STATEMENT` 的远程签名。
- 不主动发回调；不限频；批量查询只校验条数上限；任务过期只在被查询时惰性判定。
- 不校验 `tenantId/organizationId` 与签名人的归属，不存储证件号明文。
- 与 RHN 合规门禁的关系：`rhn.crypto.enforce-compliance=true` 时 RHN 注册表拒绝 `DEVELOPMENT_ONLY` 适配器，联调适配器需按上面第 1 点处理。
