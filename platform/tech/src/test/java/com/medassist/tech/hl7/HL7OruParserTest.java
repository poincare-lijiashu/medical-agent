package com.medassist.tech.hl7;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HL7 v2 ORU 解析：分隔符、转义、缺字段与异常标志。
 */
class HL7OruParserTest {

    private static final String CR = "\r";

    private String message(String controlId, String applicationNo, String obx) {
        return "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|" + controlId + "|P|2.5" + CR
                + "PID|1||P001" + CR
                + "OBR|1|" + applicationNo + "|LAB001^血常规|" + CR
                + obx;
    }

    @Test
    @DisplayName("正常报文：读出控制 ID、申请号与 OBX 结果")
    void parsesStandardMessage() {
        String obx = "OBX|1|NM|LAB-K^血清钾||4.2|mmol/L|3.5-5.3|N|||F" + CR
                + "OBX|2|NM|LAB-NA^血清钠||150|mmol/L|137-147|HH|||F" + CR;

        HL7OruParser.Oru oru = HL7OruParser.parse(message("MSG001", "AP20261101000001", obx));

        assertThat(oru.valid()).isTrue();
        assertThat(oru.controlId()).isEqualTo("MSG001");
        assertThat(oru.applicationNo()).isEqualTo("AP20261101000001");
        assertThat(oru.messageType()).isEqualTo("ORU^R01");
        assertThat(oru.sendingApp()).isEqualTo("LIS");
        assertThat(oru.observations()).hasSize(2);
        assertThat(oru.observations().get(0).itemCode()).isEqualTo("LAB-K");
        assertThat(oru.observations().get(0).itemName()).isEqualTo("血清钾");
        assertThat(oru.observations().get(0).value()).isEqualTo("4.2");
        assertThat(oru.observations().get(0).unit()).isEqualTo("mmol/L");
        assertThat(oru.observations().get(0).referenceRange()).isEqualTo("3.5-5.3");
        assertThat(oru.observations().get(0).abnormal()).isFalse();
        assertThat(oru.observations().get(1).abnormal()).isTrue();
        assertThat(oru.observations().get(1).critical()).isTrue();
    }

    @Test
    @DisplayName("转义字符还原：\\F\\ \\S\\ \\E\\ 不能把字段切错")
    void unescapesSpecialCharacters() {
        String obx = "OBX|1|ST|X^A\\F\\B\\S\\C\\E\\D||值|unit||N" + CR;

        HL7OruParser.Oru oru = HL7OruParser.parse(message("MSG002", "AP1", obx));

        assertThat(oru.valid()).isTrue();
        assertThat(oru.observations().get(0).itemName()).isEqualTo("A|B^C\\D");
    }

    @Test
    @DisplayName("LF 分隔与 CRLF 都能解析")
    void acceptsLfAndCrlf() {
        String lf = message("MSG003", "AP1", "OBX|1|NM|K^钾||4.0|mmol/L||N")
                .replace("\r", "\n");
        String crlf = message("MSG004", "AP1", "OBX|1|NM|K^钾||4.0|mmol/L||N")
                .replace("\r", "\r\n");

        assertThat(HL7OruParser.parse(lf).valid()).isTrue();
        assertThat(HL7OruParser.parse(crlf).valid()).isTrue();
        assertThat(HL7OruParser.parse(crlf).observations()).hasSize(1);
    }

    @Test
    @DisplayName("缺 MSH-10：拒绝（幂等键不能为空）")
    void rejectsMissingControlId() {
        String raw = "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01||P|2.5" + CR
                + "OBR|1|AP1|X^项目|" + CR + "OBX|1|NM|K^钾||4.0|mmol/L||N" + CR;

        HL7OruParser.Oru oru = HL7OruParser.parse(raw);

        assertThat(oru.valid()).isFalse();
        assertThat(oru.errors()).anyMatch(e -> e.contains("MSH-10"));
    }

    @Test
    @DisplayName("非 ORU 类型：拒绝并提示")
    void rejectsNonOruMessage() {
        String raw = "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261101120000||ORM^O01|M1|P|2.5" + CR
                + "OBR|1|AP1|X^项目|" + CR + "OBX|1|NM|K^钾||4.0|mmol/L||N" + CR;

        HL7OruParser.Oru oru = HL7OruParser.parse(raw);

        assertThat(oru.valid()).isFalse();
        assertThat(oru.errors()).anyMatch(e -> e.contains("ORU"));
    }

    @Test
    @DisplayName("缺申请号 / 缺 OBX：都拒绝")
    void rejectsMissingApplicationOrObservations() {
        String noApp = "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|M2|P|2.5" + CR
                + "OBX|1|NM|K^钾||4.0|mmol/L||N" + CR;
        String noObx = "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261101120000||ORU^R01|M3|P|2.5" + CR
                + "OBR|1|AP1|X^项目|" + CR;

        assertThat(HL7OruParser.parse(noApp).errors()).anyMatch(e -> e.contains("申请号"));
        assertThat(HL7OruParser.parse(noObx).errors()).anyMatch(e -> e.contains("OBX"));
    }

    @Test
    @DisplayName("空消息与超长消息：拒绝")
    void rejectsEmptyAndOversized() {
        assertThat(HL7OruParser.parse(" ").valid()).isFalse();
        String huge = "x".repeat(HL7OruParser.MAX_MESSAGE_LENGTH + 1);
        assertThat(HL7OruParser.parse(huge).errors())
                .anyMatch(e -> e.contains("长度超过上限"));
    }

    @Test
    @DisplayName("未闭合转义符：按普通字符处理，不吞内容")
    void unclosedEscapeKeptAsIs() {
        String obx = "OBX|1|ST|X^A\\Q||v|u||N" + CR;

        HL7OruParser.Oru oru = HL7OruParser.parse(message("MSG005", "AP1", obx));

        assertThat(oru.valid()).isTrue();
        assertThat(oru.observations().get(0).itemName()).contains("A");
    }
}
