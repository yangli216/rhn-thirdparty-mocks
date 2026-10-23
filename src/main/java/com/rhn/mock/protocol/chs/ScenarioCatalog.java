package com.rhn.mock.protocol.chs;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 1101 人员鉴权时按证件号选择的测试人员与故障场景（需求 §4）。 */
@Component
public class ScenarioCatalog {
    public enum Behavior {
        NORMAL, MULTI, SUSPENDED, NO_INSU, REJECT_2206, IMBALANCE, MISSING_AMT, SLOW, HTTP500, BADJSON, SIGN_EXPIRE
    }

    /** 一条参保信息（insuinfo[]）。 */
    public record InsuRow(String insutype, String stas, String balc, String psnType, String admdvs) {
    }

    /** 某证件号对应的人员参保信息与场景行为。 */
    public record Fixture(Behavior behavior, List<InsuRow> rows) {
        /** 客户端取法：优先正常参保（psn_insu_stas=1），否则取首条。 */
        public InsuRow activeRow() {
            for (InsuRow row : rows) if ("1".equals(row.stas())) return row;
            return rows.get(0);
        }

        public Map<String, String> regionByInsutype() {
            java.util.LinkedHashMap<String, String> regions = new java.util.LinkedHashMap<>();
            for (InsuRow row : rows) regions.putIfAbsent(row.insutype(), row.admdvs());
            return Map.copyOf(regions);
        }
    }

    public Fixture fixtureFor(String certNo) {
        String key = certNo == null ? "" : certNo.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "TEST-390" -> new Fixture(Behavior.NORMAL, List.of(row("390", "1", null, "12", "360100")));
            case "TEST-MULTI" -> new Fixture(Behavior.MULTI,
                    List.of(row("390", "3", null, "12", "360200"), row("310", "1", "1850.00", "11", "360100")));
            case "TEST-SUSPENDED" -> new Fixture(Behavior.SUSPENDED, List.of(row("310", "2", "1850.00", "11", "360100")));
            case "TEST-NOINSU" -> new Fixture(Behavior.NO_INSU, List.of());
            case "TEST-REJECT-2206" -> new Fixture(Behavior.REJECT_2206, List.of(row("310", "1", "1850.00", "11", "360100")));
            case "TEST-IMBALANCE" -> new Fixture(Behavior.IMBALANCE, List.of(row("310", "1", "1850.00", "11", "360100")));
            case "TEST-MISSING-AMT" -> new Fixture(Behavior.MISSING_AMT, List.of(row("310", "1", "1850.00", "11", "360100")));
            case "TEST-SLOW" -> new Fixture(Behavior.SLOW, List.of(row("310", "1", "1850.00", "11", "360100")));
            case "TEST-HTTP500" -> new Fixture(Behavior.HTTP500, List.of(row("310", "1", "1850.00", "11", "360100")));
            case "TEST-BADJSON" -> new Fixture(Behavior.BADJSON, List.of(row("310", "1", "1850.00", "11", "360100")));
            case "TEST-SIGN-EXPIRE" -> new Fixture(Behavior.SIGN_EXPIRE, List.of(row("310", "1", "1850.00", "11", "360100")));
            // TEST-310 与未匹配证件号：保证向导可自由输入测试人员
            default -> new Fixture(Behavior.NORMAL, List.of(row("310", "1", "1850.00", "11", "360100")));
        };
    }

    public boolean isEmployee(String insutype) {
        return "310".equals(insutype);
    }

    public static BigDecimal money(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static InsuRow row(String insutype, String stas, String balc, String psnType, String admdvs) {
        return new InsuRow(insutype, stas, balc, psnType, admdvs);
    }
}
