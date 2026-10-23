# 医保（chs）Mock 协议补充

基础接口（报文外壳、9001/1101/2201/2203A/2204/2206/2207/2208（另有本文 §5 的 2205）、测试人员 `TEST-*`、`setlinfo` 守恒）以 RHN 仓库 `docs/billing/医保Mock服务接口要求.md` §1–§7 为准，本文只记录 **mock 侧在其基础上的补充与行为变更**（对应该文档 §8“挂号场景补充”）。RHN 侧该文档只读，未改动。

## 1. 新增 2202 挂号撤销

- 请求：`input.data.{mdtrt_id, psn_no, ipt_otp_no}`（与 `HttpChsNationalInsuranceClient.cancelRegistration` 实发一致）；应答 `output` 为 `{}`。
- 校验与拒绝（均 `infcode=-1`，HTTP 200）：

| 情形 | err_msg 含 |
|---|---|
| `mdtrt_id` 未经 2201 登记 | “未登记” |
| `psn_no` 与登记人员不一致 | “不一致” |
| 该就诊存在**已结算且未撤销**的 2207 | “2208”（须先撤销结算） |
| 已撤销过的就诊再次 2202 | “已撤销” |

- 撤销后该 `mdtrt_id` 的登记、诊断、费用明细、预结算全部清除；之后对它再做 2203A/2204/2206/2207 都被拒（“就诊登记已撤销(2202)”）。
- 2202 属“业务交易”，参与 `TEST-SIGN-EXPIRE` 与签到校验。

## 2. 2204 是否要求先有 2203A：`diagnosisRequired`

- 运行时配置 `diagnosisRequired`（`PUT /mock/config`，默认取启动配置 `mock.chs.diagnosis-required=false`，`POST /mock/reset` 复位为启动值）。
- `false`（默认）：2204 不要求诊断，供“挂号时结算”（2201→2204→2206→2207，不传 2203A）联调，无需任何手动配置。
- `true`：未上传 2203A 的就诊做 2204 被拒，err_msg 含“2203A”；用于验证“平台要求挂号带诊断”的拒绝提示。
- 打开：`curl -X PUT 'localhost:18090/mock/config' -H 'Content-Type: application/json' -d '{"diagnosisRequired":true}'`；`POST /mock/reset` 复位为 false。

## 3. 同一 `mdtrt_id` 多次结算

- 2207 成功后**消耗**本轮的预结算与费用明细；同一笔结算再次 2207 被拒（err_msg 含“重复结算”）。
- 同一 `mdtrt_id` 可继续：2203A（补传诊断）→ 2204（新费用明细）→ 2206 → 2207，得到新的 `setl_id`。
- 2208 撤销后同理需要重新 2206 才能再结算。

## 4. 2208 核对

- `data.{setl_id, mdtrt_id, psn_no}` 三者必填，且 `mdtrt_id`、`psn_no` 必须与原结算一致，否则“撤销的就诊 ID／人员编号与原结算不一致”。
- 原有拒绝不变：流水不存在“未结算”、重复撤销“已撤销”。

## 5. 暂停参保（`TEST-SUSPENDED`，`psn_insu_stas=2`）

- 1101 照常返回（便于客户端展示状态）。
- 2201 拒绝：err_msg 含“暂停”。2206 同样做该校验（因 2201 已拒，暂停人员无法产生可用的就诊，2206 的拒绝只在直接构造报文时可达，未单独成测试）。

## 6. `TEST-SIGN-EXPIRE` 只失效一次

- 该人员触发的**第一个业务交易成功后**，操作员的 `sign_no` 失效，下一次业务交易返回含“签到”的拒绝；
- 客户端重新 9001 签到后恢复正常，**不会再次失效**（按人员记一次，`POST /mock/reset` 清除标记）。

## 7. 身份证号别名（`mock.chs.cert-aliases`）

- 启动配置 `mock.chs.cert-aliases`：`身份证号 -> TEST-* 人员证件号`（缺省为空），application.yml 预置了 RHN 本地演示患者 HRMOCK0001~0012 的 12 条映射，仅联调用；不经 `/mock/config` 修改。
- 1101 先把 `mdtrt_cert_no` 解析为别名目标（未命中则原值）：按目标选场景（SUSPENDED、REJECT_2206、SIGN_EXPIRE、NOINSU 等同样生效），`psn_no = "PSN-" + 目标`（如 `PSN-TEST-310`）。
- `baseinfo.certno` 与 PersonSession 记录的 `certType/certNo` 仍是请求原值（身份证号），后续 2201 以同一身份证号作就诊凭证可通过核对。
- 匹配时去首尾空格、末位 `X` 不区分大小写；未映射证件号行为不变（`PSN-<certNo>`）。
- 测试：`ChsCertAliasTest`。

## 8. 测试

`ChsRegistrationSettlementTest`（2202 全部分支、`diagnosisRequired` 开关与复位、同就诊二次结算与重复 2207、2208 核对、暂停 2201、签到一次性失效）；`ChsCertAliasTest`（身份证别名）；原 `ChsScenarioTest` / `ChsEndToEndAcceptanceTest` 保持通过。

## 9. 与真实平台的简化

2202 在真实平台上还有更多前置（如费用已退清）；mock 只按上表四条校验。`diagnosisRequired=false`（默认）是联调便利设置，真实统筹区是否要求挂号带诊断仍需平台反馈。

## 5. 2205 费用明细撤销

收费处放弃已试算的结算单时，RHN 先 2205 撤销已上传的费用明细，再 2202 撤销本申请登记的就诊。mock 行为：

- 入参 `input.data.{mdtrt_id,chrg_bchno,psn_no}`，`output` 为空对象。
- `mdtrt_id` 未登记 → “就诊未登记(2201)，不能撤销费用明细”；`psn_no` 与登记不一致 → 拒绝。
- 该就诊已结算且未撤销 → “请先撤销结算(2208)”。
- 撤销该就诊的 2204 费用明细，并连带作废其 2206 预结算（之后须重新 2204/2206）；就诊登记保持有效。
- 没有可撤销的费用明细（包括重复撤销）→ `infcode=-1`。
- 不校验 `chrg_bchno` 与上传批次是否一致（mock 费用明细按就诊保存），仅要求必填。

测试：`ChsRegistrationSettlementTest` 中 2205 两个用例。
