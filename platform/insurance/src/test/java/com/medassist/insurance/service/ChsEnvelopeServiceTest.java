package com.medassist.insurance.service;

import com.medassist.insurance.config.ChsProperties;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CHS 2.0 信封/签名/响应的纯单测。
 *
 * <p>重点：信封字段全国口径、input 模板可配置、签名可复算、响应成功码可配置。
 */
class ChsEnvelopeServiceTest {

    private static final String SECRET = "chs-secret";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChsEnvelopeService service = new ChsEnvelopeService(objectMapper);

    @Test
    @DisplayName("生成国家 2.0 信封：infno/msgid/机构/经办人/input 字段齐全，签名可复算")
    void buildsEnvelopeAndSign() throws Exception {
        ChsProperties props = props("HMAC_SHA256", Map.of("SETTLE",
                new ChsProperties.Transaction("2201", "", null)));
        SettlementBill bill = bill();
        String payload = service.buildPayload(props, bill, "MSG-001", "SETTLE");
        JsonNode root = objectMapper.readTree(payload);

        assertThat(root.path("infno").asText()).isEqualTo("2201");
        assertThat(root.path("msgid").asText()).isEqualTo("MSG-001");
        assertThat(root.path("fixmedins_code").asText()).isEqualTo("H0001");
        assertThat(root.path("fixmedins_name").asText()).isEqualTo("测试医院");
        assertThat(root.path("mdtrtarea_admvs").asText()).isEqualTo("330100");
        assertThat(root.path("insuplc_admdvs").asText()).isEqualTo("330100");
        assertThat(root.path("signtype").asText()).isEqualTo("HMAC_SHA256");
        assertThat(root.path("input").path("psn_no").asText()).isEqualTo("P0001");
        assertThat(root.path("input").path("mdtrt_id").asText())
                .isEqualTo(bill.visitId().toString());
        assertThat(root.path("input").path("chrg_bchno").asText()).isEqualTo("SB20261104000001");
        assertThat(root.path("input").path("medfee_sumamt").asText()).isEqualTo("120.00");

        String actualSign = root.path("sign").asText();
        assertThat(actualSign).hasSize(64);
        ObjectNode withoutSign = (ObjectNode) root;
        withoutSign.remove("sign");
        assertThat(actualSign).isEqualTo(hmac(objectMapper.writeValueAsString(withoutSign)));
    }

    @Test
    @DisplayName("模板可覆盖：只输出配置的字段；NONE 签名不写 sign")
    void templateAndNoneSign() throws Exception {
        ChsProperties props = props("NONE", Map.of("SETTLE", new ChsProperties.Transaction(
                "2201", "", "{\"custom_no\":\"{{patientNo}}\",\"amount\":\"{{totalAmount}}\"}")));
        JsonNode root = objectMapper.readTree(
                service.buildPayload(props, bill(), "MSG-002", "SETTLE"));

        assertThat(root.path("input").path("custom_no").asText()).isEqualTo("P0001");
        assertThat(root.path("input").path("amount").asText()).isEqualTo("120.00");
        assertThat(root.path("input").has("psn_no")).isFalse();
        assertThat(root.path("sign").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("未配置交易 → 422，不发出半成品报文")
    void missingTransaction() {
        ChsProperties props = props("HMAC_SHA256", Map.of());
        assertThatThrownBy(() -> service.buildPayload(props, bill(), "MSG-003", "SETTLE"))
                .isInstanceOf(com.medassist.common.exception.BusinessException.class)
                .hasMessageContaining("未配置医保 CHS 交易");
    }

    @Test
    @DisplayName("响应：ret_code=0 成功并取 output.setl_id；非成功码判失败")
    void parsesResponse() {
        ChsProperties props = props("HMAC_SHA256", Map.of());
        ChsEnvelopeService.ChsResponse ok = service.parse(props,
                "{\"ret_code\":\"0\",\"ret_msg\":\"成功\",\"output\":{\"setl_id\":\"S123\"}}");
        assertThat(ok.success()).isTrue();
        assertThat(ok.externalNo()).isEqualTo("S123");

        ChsEnvelopeService.ChsResponse failed = service.parse(props,
                "{\"ret_code\":\"-1\",\"ret_msg\":\"参保状态异常\"}");
        assertThat(failed.success()).isFalse();
        assertThat(failed.retMsg()).isEqualTo("参保状态异常");
    }

    private ChsProperties props(String signType,
                                Map<String, ChsProperties.Transaction> transactions) {
        return new ChsProperties(true, "http://127.0.0.1:9099", "127.0.0.1",
                java.time.Duration.ofSeconds(3), "H0001", "测试医院", "HIS",
                "330100", "330100", "V1.0", signType, SECRET, "op01", "经办人",
                "ret_code", "ret_msg", "output.setl_id", "0", transactions);
    }

    private static SettlementBill bill() {
        return new SettlementBill(UUID.randomUUID(), "SB20261104000001", UUID.randomUUID(),
                UUID.randomUUID(), "P0001", LocalDate.parse("2026-11-04"), UUID.randomUUID(),
                new BigDecimal("120.00"), new BigDecimal("120.00"), BigDecimal.ZERO,
                new BigDecimal("96.00"), new BigDecimal("24.00"), SettlementBill.CONFIRMED,
                "[]", "admin01", "admin01", Instant.now(), null, null, null,
                new BigDecimal("0.80"), new BigDecimal("300000.00"), 1L, Instant.now(),
                "J18.900", "肺炎", 1, "[]");
    }

    private static String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
