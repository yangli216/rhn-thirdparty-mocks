package com.rhn.mock.protocol.chs.web;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.core.MockRejection;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 报文外壳字段与校验（需求 §2）。 */
public final class EnvelopeKeys {
    /** 均需存在（键缺失或值为 null 视为缺失）。 */
    public static final List<String> REQUIRED_FIELDS = List.of(
            "infno", "msgid", "mdtrtarea_admvs", "insuplc_admdvs", "recer_sys_code", "dev_no", "dev_safe_info",
            "cainfo", "signtype", "infver", "opter_type", "opter", "opter_name", "inf_time", "fixmedins_code",
            "fixmedins_name", "sign_no", "input");

    /** 语义字段，除"存在"外还要求非空。 */
    public static final List<String> NON_BLANK_FIELDS = List.of("infno", "msgid", "inf_time", "opter", "fixmedins_code");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EnvelopeKeys() {
    }

    public static void validate(JsonNode root) {
        for (String field : REQUIRED_FIELDS) {
            if (!JsonNodes.present(root, field)) throw new MockRejection("缺少必填字段:" + field);
        }
        for (String field : NON_BLANK_FIELDS) {
            if (JsonNodes.text(root, field) == null) throw new MockRejection("必填字段为空:" + field);
        }
        JsonNode input = root.get("input");
        if (input == null || !input.isObject()) throw new MockRejection("input 必须是 JSON 对象");
        String infTime = JsonNodes.text(root, "inf_time");
        try {
            LocalDateTime.parse(infTime, TIME);
        } catch (RuntimeException exception) {
            throw new MockRejection("inf_time 格式必须为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
