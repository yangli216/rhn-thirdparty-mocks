package com.rhn.mock.protocol.chs;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 医保内存交易状态：重启即清空，{@code POST /mock/reset} 也可清空。 */
@Component
public class ChsMockState {
    private final Set<String> msgIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> signNos = new ConcurrentHashMap<>();
    private final Map<String, PersonSession> persons = new ConcurrentHashMap<>();
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Set<String> diagnoses = ConcurrentHashMap.newKeySet();
    private final Map<String, List<SettlementCalculator.FeeLine>> fees = new ConcurrentHashMap<>();
    private final Map<String, SettlementCalculator.Settlement> preSettlements = new ConcurrentHashMap<>();
    private final Map<String, Settled> settled = new ConcurrentHashMap<>();
    private final Set<String> cancelledRegistrations = ConcurrentHashMap.newKeySet();
    private final Set<String> signExpired = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    public record PersonSession(String psnNo, String certType, String certNo, String insutype,
                                ScenarioCatalog.Behavior behavior, BigDecimal balc) {
    }

    public record Registration(String mdtrtId, String psnNo, String insutype, String certType, String certNo) {
    }

    public record Settled(String setlId, String mdtrtId, String psnNo, boolean reversed) {
    }

    /** 返回 true 表示首次出现。 */
    public boolean markMsgId(String msgid) {
        return msgIds.add(msgid);
    }

    public void putSignNo(String opter, String signNo) {
        signNos.put(opter, signNo);
    }

    /** 校验 sign_no 由本服务签发且对应当前 opter。 */
    public boolean matchesSignNo(String opter, String signNo) {
        return opter != null && signNo != null && signNo.equals(signNos.get(opter));
    }

    public void removeSignNo(String opter) {
        if (opter != null) signNos.remove(opter);
    }

    public void putPerson(PersonSession session) {
        persons.put(session.psnNo(), session);
    }

    public PersonSession person(String psnNo) {
        return psnNo == null ? null : persons.get(psnNo);
    }

    public void putRegistration(Registration registration) {
        registrations.put(registration.mdtrtId(), registration);
    }

    public Registration registration(String mdtrtId) {
        return mdtrtId == null ? null : registrations.get(mdtrtId);
    }

    public void markDiagnosis(String mdtrtId) {
        diagnoses.add(mdtrtId);
    }

    public boolean hasDiagnosis(String mdtrtId) {
        return diagnoses.contains(mdtrtId);
    }

    public void putFees(String mdtrtId, List<SettlementCalculator.FeeLine> lines) {
        fees.put(mdtrtId, List.copyOf(lines));
    }

    public List<SettlementCalculator.FeeLine> fees(String mdtrtId) {
        return mdtrtId == null ? null : fees.get(mdtrtId);
    }

    /** 撤销费用明细：连同基于它的预结算一并作废；原先没有费用明细则返回 false。 */
    public boolean cancelFees(String mdtrtId) {
        preSettlements.remove(mdtrtId);
        return fees.remove(mdtrtId) != null;
    }

    public void putPreSettlement(String mdtrtId, SettlementCalculator.Settlement settlement) {
        preSettlements.put(mdtrtId, settlement);
    }

    public SettlementCalculator.Settlement preSettlement(String mdtrtId) {
        return mdtrtId == null ? null : preSettlements.get(mdtrtId);
    }

    /** 该就诊最近一笔结算是否仍有效（已结算且未撤销）。 */
    public boolean hasActiveSettlement(String mdtrtId) {
        return settled.values().stream().anyMatch(item -> item.mdtrtId().equals(mdtrtId) && !item.reversed());
    }

    /** 记录结算并消耗本轮预结算与费用明细：同一笔不能重复 2207，后续结算须重新 2204/2206。 */
    public void recordSettlement(String setlId, String mdtrtId, String psnNo) {
        settled.put(setlId, new Settled(setlId, mdtrtId, psnNo, false));
        preSettlements.remove(mdtrtId);
        fees.remove(mdtrtId);
    }

    public boolean isRegistrationCancelled(String mdtrtId) {
        return cancelledRegistrations.contains(mdtrtId);
    }

    public void cancelRegistration(String mdtrtId) {
        registrations.remove(mdtrtId);
        preSettlements.remove(mdtrtId);
        fees.remove(mdtrtId);
        diagnoses.remove(mdtrtId);
        cancelledRegistrations.add(mdtrtId);
    }

    /** 返回 true 表示首次触发 TEST-SIGN-EXPIRE 失效（每个人员只失效一次）。 */
    public boolean markSignExpired(String psnNo) {
        return psnNo != null && signExpired.add(psnNo);
    }

    public Settled settled(String setlId) {
        return setlId == null ? null : settled.get(setlId);
    }

    public void markReversed(String setlId) {
        settled.computeIfPresent(setlId, (key, value) -> new Settled(value.setlId(), value.mdtrtId(), value.psnNo(), true));
    }

    public String nextId(String prefix, int width) {
        return prefix + String.format("%0" + width + "d", sequence.incrementAndGet());
    }

    public synchronized void reset() {
        msgIds.clear();
        signNos.clear();
        persons.clear();
        registrations.clear();
        diagnoses.clear();
        fees.clear();
        preSettlements.clear();
        cancelledRegistrations.clear();
        signExpired.clear();
        settled.clear();
        sequence.set(0);
    }
}
