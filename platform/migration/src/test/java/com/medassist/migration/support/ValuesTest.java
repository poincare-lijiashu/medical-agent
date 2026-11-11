package com.medassist.migration.support;

import com.medassist.migration.catalog.TargetCatalog;
import com.medassist.migration.domain.MigrationModel.FieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 归一化口径：同一份数据在预览、校验和、入库、回滚比较中必须得到同一个字符串。
 */
class ValuesTest {

    private final TargetCatalog.Spec patient = TargetCatalog.require("EMPI_PATIENT");
    private final TargetCatalog.Spec visit = TargetCatalog.require("CLINICAL_VISIT");

    @Test
    @DisplayName("日期：yyyyMMdd / yyyy/M/d / ISO 都归一化为 yyyy-MM-dd")
    void dates() {
        FieldDef birth = patient.field("birth_date");
        assertThat(Values.normalize(birth, "19900102", null)).isEqualTo("1990-01-02");
        assertThat(Values.normalize(birth, "1990/1/2", null)).isEqualTo("1990-01-02");
        assertThat(Values.normalize(birth, "1990-01-02", null)).isEqualTo("1990-01-02");
    }

    @Test
    @DisplayName("时间：本地时间按业务时区加偏移量，带偏移量的 ISO 保持不变")
    void instants() {
        FieldDef registered = visit.field("registered_at");
        String local = Values.normalize(registered, "2026-01-02 08:30:00", null);
        assertThat(OffsetDateTime.parse(local).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-01-02T08:30:00+08:00").toInstant());

        String iso = Values.normalize(registered, "2026-01-02T00:30:00Z", null);
        assertThat(iso).isEqualTo("2026-01-02T08:30:00+08:00");
    }

    @Test
    @DisplayName("数字：去尾零，拒绝非整数与非数字")
    void numbers() {
        FieldDef price = TargetCatalog.require("CLINICAL_ORDER").field("unit_price");
        assertThat(Values.normalize(price, "10.00", null)).isEqualTo("10");
        assertThat(Values.normalize(price, " 1,234.50 ", null)).isEqualTo("1234.5");

        FieldDef quantity = TargetCatalog.require("CLINICAL_ORDER").field("quantity");
        assertThatThrownBy(() -> Values.normalize(quantity, "1.5", null))
                .hasMessageContaining("必须是整数");
    }

    @Test
    @DisplayName("布尔与枚举别名：是/否、男/女、中文状态都能识别")
    void boolAndEnum() {
        FieldDef enabled = TargetCatalog.require("CLINICAL_DEPT").field("enabled");
        assertThat(Values.normalize(enabled, "是", null)).isEqualTo("true");
        assertThat(Values.normalize(enabled, "N", null)).isEqualTo("false");

        Map<String, String> genderAliases = patient.enumAliases().get("gender");
        assertThat(Values.normalize(patient.field("gender"), "男", genderAliases)).isEqualTo("MALE");

        Map<String, String> visitStatus = visit.enumAliases().get("status");
        assertThat(Values.normalize(visit.field("status"), "已完成", visitStatus)).isEqualTo("FINISHED");
    }

    @Test
    @DisplayName("数据库值比较：时间/数字/布尔的规范形式与归一化一致")
    void canonicalDbValues() {
        FieldDef registered = visit.field("registered_at");
        assertThat(Values.canonicalDbValue(registered,
                OffsetDateTime.parse("2026-01-02T00:30:00Z"))).isEqualTo("2026-01-02T08:30:00+08:00");

        FieldDef amount = TargetCatalog.require("CLINICAL_ORDER").field("amount");
        assertThat(Values.canonicalDbValue(amount, new BigDecimal("10.00"))).isEqualTo("10");
        assertThat(Values.canonicalDbValue(visit.field("visit_date"), LocalDate.of(2026, 1, 2)))
                .isEqualTo("2026-01-02");
    }

    @Test
    @DisplayName("敏感列识别：兼容旧系统列名，但不误伤“证件类型/手机归属地”")
    void sensitiveColumns() {
        assertThat(Values.sensitive("id_no")).isTrue();
        assertThat(Values.sensitive("患者证件号码")).isTrue();
        assertThat(Values.sensitive("身份证号")).isTrue();
        assertThat(Values.sensitive("家庭住址")).isTrue();
        assertThat(Values.sensitive("证件类型")).isFalse();
        assertThat(Values.sensitive("手机归属地")).isFalse();
        assertThat(Values.sensitive("科室名称")).isFalse();
    }

    @Test
    @DisplayName("掩码：保留首尾各一位，短值不泄露完整内容")
    void masking() {
        assertThat(Values.mask("110101199001011234")).isEqualTo("1******4");
        assertThat(Values.mask("13800001111")).isEqualTo("1******1");
        assertThat(Values.mask("ab")).isEqualTo("**");
        assertThat(Values.mask(null)).isNull();
    }
}
