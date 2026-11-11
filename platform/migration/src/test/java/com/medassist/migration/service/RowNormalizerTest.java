package com.medassist.migration.service;

import com.medassist.migration.catalog.TargetCatalog;
import com.medassist.migration.domain.MigrationModel.MappingProfile;
import com.medassist.migration.domain.MigrationModel.MappingRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行归一化：默认值、业务规则、映射档案与脱敏投影。
 */
class RowNormalizerTest {

    private final RowNormalizer normalizer = new RowNormalizer();

    @Test
    @DisplayName("患病档案：中文性别/证件类型转换，空白状态回落 ACTIVE，敏感值掩码")
    void patientNormalization() {
        TargetCatalog.Spec spec = TargetCatalog.require("EMPI_PATIENT");
        Map<String, String> raw = row(
                "patient_no", "P001",
                "name", "张三",
                "gender", "男",
                "id_type", "身份证",
                "id_no", "110101199001011234",
                "phone", "13800001111",
                "address", "北京市东城区 1 号",
                "status", "");

        NormalizedRow result = normalizer.normalize(spec, null, raw);

        assertThat(result.values()).containsEntry("gender", "MALE")
                .containsEntry("id_type", "ID_CARD")
                .containsEntry("status", "ACTIVE");
        assertThat(result.normalizedProjection())
                .containsEntry("id_no", "1******4")
                .containsEntry("phone", "1******1");
        assertThat(result.rawProjection()).containsEntry("id_no", "1******4")
                .containsEntry("address", "北******号");
    }

    @Test
    @DisplayName("必填缺失：直接给出中文字段名与行内列名")
    void requiredField() {
        TargetCatalog.Spec spec = TargetCatalog.require("EMPI_PATIENT");
        assertThatThrownBy(() -> normalizer.normalize(spec, null,
                row("patient_no", "P001", "name", "")))
                .isInstanceOf(RowProblem.class)
                .hasMessageContaining("姓名");
    }

    @Test
    @DisplayName("医嘱金额：缺失时按数量×单价计算，不一致时拒绝整行")
    void orderAmount() {
        TargetCatalog.Spec spec = TargetCatalog.require("CLINICAL_ORDER");
        Map<String, String> raw = row(
                "order_no", "O001", "visit_no", "V001", "order_type", "药品",
                "item_code", "DRG-1", "item_name", "阿莫西林", "quantity", "3",
                "unit", "盒", "unit_price", "12.50", "ordered_by", "D001");

        NormalizedRow result = normalizer.normalize(spec, null, raw);
        assertThat(result.values()).containsEntry("amount", "37.5")
                .containsEntry("order_type", "DRUG")
                .containsEntry("status", "PLACED");

        Map<String, String> wrong = row(
                "order_no", "O002", "visit_no", "V001", "order_type", "DRUG",
                "item_code", "DRG-1", "item_name", "阿莫西林", "quantity", "3",
                "unit", "盒", "unit_price", "12.50", "amount", "30", "ordered_by", "D001");
        assertThatThrownBy(() -> normalizer.normalize(spec, null, wrong))
                .hasMessageContaining("金额与数量×单价不一致");
    }

    @Test
    @DisplayName("住院出院：状态 DISCHARGED 必须有出院时间")
    void admissionDischarge() {
        TargetCatalog.Spec spec = TargetCatalog.require("CLINICAL_ADMISSION");
        assertThatThrownBy(() -> normalizer.normalize(spec, null, row(
                "admission_no", "A001", "visit_no", "V001", "patient_no", "P001",
                "ward_code", "W01", "attending_doctor", "D001", "status", "DISCHARGED")))
                .hasMessageContaining("必须提供出院时间");
    }

    @Test
    @DisplayName("映射档案：中文源列、枚举映射、默认值与常量都能落到目标字段")
    void mappingProfile() {
        TargetCatalog.Spec spec = TargetCatalog.require("EMPI_PATIENT");
        MappingProfile profile = new MappingProfile(null, "HIS_PATIENT", "旧 HIS 患者",
                "HIS", "EMPI_PATIENT", List.of(
                new MappingRule("住院号", "patient_no", "TRIM", null, null),
                new MappingRule("姓名", "name", "TRIM", null, null),
                new MappingRule("性别", "gender", "ENUM_MAP", null,
                        Map.of("男", "MALE", "女", "FEMALE")),
                new MappingRule("证件号", "id_no", "TRIM", null, null),
                new MappingRule("来源", "status", "CONSTANT", "ACTIVE", null)),
                "ACTIVE", 0, "tester", null, null);

        Map<String, String> raw = row(
                "住院号", " P100 ", "姓名", "李四", "性别", "女", "证件号", "")
                ;
        NormalizedRow result = normalizer.normalize(spec, profile, raw);

        assertThat(result.values()).containsEntry("patient_no", "P100")
                .containsEntry("gender", "FEMALE")
                .containsEntry("status", "ACTIVE");
        assertThat(result.values().get("id_no")).isNull();
        assertThat(result.provided()).contains("patient_no", "status", "id_no");
    }

    private static Map<String, String> row(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
