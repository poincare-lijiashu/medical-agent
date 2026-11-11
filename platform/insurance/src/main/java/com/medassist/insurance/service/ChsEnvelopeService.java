package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.config.ChsProperties;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国家医保 2.0（CHS）信封构建、签名与响应解析。
 *
 * <p>信封字段按全国统一规范固定（infno/msgid/mdtrtarea_admvs/...），
 * 业务 {@code input} 用可配置模板生成：换统筹区只改配置，不改代码。
 *
 * <p>模板占位符为 {@code {{field}}}（双花括号），刻意不用 {@code ${...}}：
 * 后者会被 Spring 环境当成配置占位符解析，模板里写业务字段名会直接启动失败。
 */
@Service
public class ChsEnvelopeService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final DateTimeFormatter INF_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 默认 input 模板：只放**全国口径相对稳定、且本地确实有值**的字段。
     * 不放 med_type/诊断/明细等地区差异大的字段，避免"看起来完整但填错"。
     */
    static final String DEFAULT_TEMPLATE = """
            {"psn_no":"{{patientNo}}","mdtrt_id":"{{visitId}}","chrg_bchno":"{{billNo}}",
             "medfee_sumamt":"{{totalAmount}}","fund_pay_sumamt":"{{insuranceAmount}}",
             "psn_pay":"{{selfPayAmount}}","setl_time":"{{serviceDate}}"}
            """;

    private final ObjectMapper objectMapper;

    public ChsEnvelopeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 构建完整 CHS 报文（含 sign 字段）。 */
    public String buildPayload(ChsProperties props, SettlementBill bill, String requestId,
                               String bizType) {
        ChsProperties.Transaction transaction = props.transaction(bizType);
        if (transaction == null || transaction.infno() == null
                || transaction.infno().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "未配置医保 CHS 交易：" + bizType + "（insurance.chs.transactions）");
        }
        Map<String, Object> input = inputOf(transaction.template(), bill, requestId, bizType);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("infno", transaction.infno().trim());
        envelope.put("msgid", requestId);
        envelope.put("mdtrtarea_admvs", props.mdtrtareaAdmvsOrEmpty());
        envelope.put("insuplc_admdvs", props.insuplcAdmdvsOrEmpty());
        envelope.put("recer_sys_code", props.recerSysCodeOrDefault());
        envelope.put("dev_no", "");
        envelope.put("dev_safe_info", "");
        envelope.put("cainfo", "");
        envelope.put("signtype", props.signTypeOrDefault());
        envelope.put("infver", props.infVerOrDefault());
        envelope.put("opter", props.opterOrDefault());
        envelope.put("opter_name", props.opterNameOrDefault());
        envelope.put("inf_time", LocalDateTime.now().format(INF_TIME));
        envelope.put("fixmedins_code", props.fixmedinsCodeOrEmpty());
        envelope.put("fixmedins_name", props.fixmedinsNameOrEmpty());
        envelope.put("input", input);

        String sign = sign(props, serialize(envelope));
        if (sign != null) {
            envelope.put("sign", sign);
        }
        return serialize(envelope);
    }

    /**
     * 计算签名：对**不含 sign 的信封 JSON** 计算，返回十六进制小写。
     * {@code NONE} 返回 null（由前置机/CA 另行签名）。
     */
    public String sign(ChsProperties props, String canonicalJson) {
        String type = props.signTypeOrDefault();
        return switch (type) {
            case "HMAC_SHA256" -> hmacSha256(canonicalJson, requireSecret(props));
            case "MD5" -> md5(canonicalJson);
            case "NONE" -> null;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "不支持的医保签名方式：" + type + "（可选 HMAC_SHA256 / MD5 / NONE）");
        };
    }

    /** 解析 CHS 响应；业务失败由调用方决定落 FAILED（这里只做结构解析）。 */
    public ChsResponse parse(ChsProperties props, String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("医保返回空响应");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (JacksonException e) {
            throw new IllegalStateException("医保响应不是合法 JSON");
        }
        String retCode = root.path(props.retCodeFieldOrDefault()).asText("").trim();
        String retMsg = root.path(props.retMsgFieldOrDefault()).asText("").trim();
        String externalNo = textAtPath(root, props.settlementIdPathOrDefault());
        if (externalNo == null || externalNo.isBlank()) {
            externalNo = textAtPath(root, "output.setl_id");
        }
        return new ChsResponse(externalNo, retCode, retMsg,
                props.successCodeSet().contains(retCode));
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> inputOf(String template, SettlementBill bill,
                                        String requestId, String bizType) {
        String source = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("billId", bill.id().toString());
        values.put("billNo", bill.billNo());
        values.put("visitId", bill.visitId().toString());
        values.put("patientId", bill.patientId().toString());
        values.put("patientNo", bill.patientNo());
        values.put("totalAmount", bill.totalAmount().toPlainString());
        values.put("eligibleAmount", bill.eligibleAmount().toPlainString());
        values.put("deductible", bill.deductible().toPlainString());
        values.put("insuranceAmount", bill.insuranceAmount().toPlainString());
        values.put("selfPayAmount", bill.selfPayAmount().toPlainString());
        values.put("serviceDate", bill.serviceDate() == null
                ? "" : bill.serviceDate().toString());
        values.put("requestId", requestId);
        values.put("bizType", bizType);

        Matcher matcher = PLACEHOLDER.matcher(source);
        StringBuilder rendered = new StringBuilder(source.length() + 64);
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(jsonEscape(value)));
        }
        matcher.appendTail(rendered);
        try {
            return objectMapper.readValue(rendered.toString(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "医保 CHS input 模板不是合法 JSON，请检查 insurance.chs.transactions 配置");
        }
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "医保 CHS 报文序列化失败");
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String requireSecret(ChsProperties props) {
        String secret = props.secretOrEmpty();
        if (secret.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置医保 CHS 签名密钥（insurance.chs.secret 或 CHS_SECRET）");
        }
        return secret;
    }

    private static String hmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("医保 CHS 签名失败：" + e.getMessage());
        }
    }

    private static String md5(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                    digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("医保 CHS MD5 签名失败：" + e.getMessage());
        }
    }

    private static String textAtPath(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null || part.isBlank()) {
                return null;
            }
            node = node.path(part);
        }
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    /** CHS 响应解析结果；{@code success} 由配置的成功码集合决定。 */
    public record ChsResponse(String externalNo, String retCode, String retMsg, boolean success) {
    }
}
