package com.medassist.migration.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标目录自检：字段、绑定、引用、冲突检查必须自洽，否则模板与执行口径会错位。
 */
class TargetCatalogTest {

    @Test
    @DisplayName("13 个目标全部注册，排序号唯一且覆盖主数据/患者/临床三类")
    void allTargets() {
        var specs = TargetCatalog.all();

        assertThat(specs).hasSize(13);
        assertThat(specs).extracting(TargetCatalog.Spec::code).contains(
                "CLINICAL_DEPT", "EMPI_PATIENT", "EMPI_CROSSWALK",
                "CLINICAL_VISIT", "CLINICAL_ADMISSION", "CLINICAL_ORDER",
                "CLINICAL_CHARGE", "CLINICAL_CHARGE_ITEM");
        assertThat(specs).extracting(TargetCatalog.Spec::code).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("每个绑定/引用都指向已声明字段，matchFields 与字段字典一致")
    void bindingsAreConsistent() {
        for (TargetCatalog.Spec spec : TargetCatalog.all()) {
            Set<String> fields = spec.fieldKeys();
            assertThat(spec.fields()).as(spec.code()).isNotEmpty();
            for (TargetCatalog.ColumnBind bind : spec.binds()) {
                assertThat(fields).as(spec.code() + " 绑定字段 " + bind.field())
                        .contains(bind.field());
            }
            for (TargetCatalog.Sensitive sensitive : spec.sensitive()) {
                assertThat(fields).contains(sensitive.field());
            }
            for (TargetCatalog.Resolution resolution : spec.resolutions()) {
                assertThat(resolution.fields()).allMatch(fields::contains);
                assertThat(resolution.column()).isNotBlank();
                assertThat(resolution.sql()).containsIgnoringCase("select");
            }
            assertThat(fields).containsAll(spec.matchFields());
            assertThat(spec.matchSql()).isNotBlank();
            assertThat(spec.pkColumn()).isNotBlank();
        }
    }

    @Test
    @DisplayName("敏感字段必须加密列，且有哈希列的字段不能是住址等长文本之外的误配")
    void sensitiveColumns() {
        TargetCatalog.Spec patient = TargetCatalog.require("EMPI_PATIENT");
        assertThat(patient.sensitive()).extracting(TargetCatalog.Sensitive::field)
                .containsExactly("id_no", "phone", "address");
        assertThat(patient.sensitive()).filteredOn(s -> s.hashColumn() != null)
                .extracting(TargetCatalog.Sensitive::field)
                .containsExactlyInAnyOrder("id_no", "phone");
    }

    @Test
    @DisplayName("关键冲突检查必须存在：患者证件号、就诊在途唯一、收费单一对一")
    void criticalChecks() {
        assertThat(TargetCatalog.require("EMPI_PATIENT").checks())
                .anyMatch(c -> c.message().contains("证件号"));
        assertThat(TargetCatalog.require("CLINICAL_VISIT").checks())
                .anyMatch(c -> c.message().contains("在途就诊"));
        assertThat(TargetCatalog.require("CLINICAL_CHARGE").checks())
                .anyMatch(c -> c.message().contains("一张收费单"));
    }

    @Test
    @DisplayName("默认值只能落在已声明字段上")
    void defaultsAreDeclared() {
        for (TargetCatalog.Spec spec : TargetCatalog.all()) {
            Set<String> fields = new HashSet<>(spec.fieldKeys());
            assertThat(spec.defaults().keySet()).as(spec.code()).allMatch(fields::contains);
        }
    }
}
