package com.medassist.insurance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 国家医保信息平台 2.0（CHS）报文配置。
 *
 * <p>为什么"国家基线 + 可配置交易/模板"而不是写死某省：
 * <ul>
 *   <li>信封字段（infno/msgid/mdtrtarea_admvs/...）全国一致，可以固化；</li>
 *   <li>交易号、路径、业务 input 字段与成功码各统筹区有差异，必须可配置——
 *       写死一套字段，换一个城市就要改代码；</li>
 *   <li>签名方式由院方前置机/CA 决定，这里只提供 HMAC-SHA256/MD5/NONE 三种，
 *       需要国密时由前置机签名（NONE）。</li>
 * </ul>
 *
 * <p>默认关闭：未配置时不改变现有实时结算行为，避免"升级即改口径"。
 */
@ConfigurationProperties("insurance.chs")
public record ChsProperties(
        Boolean enabled,
        String baseUrl,
        String allowedHosts,
        Duration timeout,
        String fixmedinsCode,
        String fixmedinsName,
        String recerSysCode,
        String mdtrtareaAdmvs,
        String insuplcAdmdvs,
        String infVer,
        String signType,
        String secret,
        String opter,
        String opterName,
        String retCodeField,
        String retMsgField,
        String settlementIdPath,
        String successCodes,
        Map<String, Transaction> transactions) {

    /**
     * @param infno    医保交易编号（如 2201 门诊结算、2301 住院结算，按当地规范配置）
     * @param path     相对 base-url 的路径；为空表示 base-url 就是完整端点
     * @param template 业务 input 的 JSON 模板，占位符用 {{field}}（见 ChsEnvelopeService）
     */
    public record Transaction(String infno, String path, String template) {
    }

    public static ChsProperties disabled() {
        return new ChsProperties(false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }

    public String baseUrlOrEmpty() {
        return baseUrl == null ? "" : baseUrl.trim();
    }

    public String fixmedinsCodeOrEmpty() {
        return fixmedinsCode == null ? "" : fixmedinsCode.trim();
    }

    public String fixmedinsNameOrEmpty() {
        return fixmedinsName == null ? "" : fixmedinsName.trim();
    }

    public String recerSysCodeOrDefault() {
        return blankToDefault(recerSysCode, "HIS");
    }

    public String mdtrtareaAdmvsOrEmpty() {
        return mdtrtareaAdmvs == null ? "" : mdtrtareaAdmvs.trim();
    }

    public String insuplcAdmdvsOrEmpty() {
        return insuplcAdmdvs == null ? "" : insuplcAdmdvs.trim();
    }

    public String infVerOrDefault() {
        return blankToDefault(infVer, "V1.0");
    }

    public String signTypeOrDefault() {
        return blankToDefault(signType, "HMAC_SHA256").toUpperCase(Locale.ROOT);
    }

    public String secretOrEmpty() {
        return secret == null ? "" : secret.trim();
    }

    public String opterOrDefault() {
        return blankToDefault(opter, "medassist");
    }

    public String opterNameOrDefault() {
        return blankToDefault(opterName, "MedAssist");
    }

    public String retCodeFieldOrDefault() {
        return blankToDefault(retCodeField, "ret_code");
    }

    public String retMsgFieldOrDefault() {
        return blankToDefault(retMsgField, "ret_msg");
    }

    public String settlementIdPathOrDefault() {
        return blankToDefault(settlementIdPath, "output.setl_id");
    }

    public Set<String> successCodeSet() {
        return splitCsv(blankToDefault(successCodes, "0"));
    }

    public Set<String> allowedHostSet() {
        return splitCsv(allowedHosts);
    }

    public Duration timeoutOrDefault() {
        return timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(10) : timeout;
    }

    public Transaction transaction(String bizType) {
        if (transactions == null || bizType == null) {
            return null;
        }
        return transactions.get(bizType);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Set<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 仅测试/文档用：列出已配置交易。 */
    public List<String> transactionNames() {
        return transactions == null ? List.of() : transactions.keySet().stream().sorted().toList();
    }
}
