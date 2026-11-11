package com.medassist.insurance.grouper;

import com.medassist.insurance.domain.InsuranceModel.GrouperAdrgRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcExclusion;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperDipCatalog;
import com.medassist.insurance.domain.InsuranceModel.GrouperDrgDef;
import com.medassist.insurance.domain.InsuranceModel.GrouperMdcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地分组引擎：MDC→ADRG→CC/MCC→DRG 与 DIP 病种匹配的可解释性。
 *
 * <p>规则来自"导入的规则包"，测试里用最小规则集构造；断言重点是
 * 严重程度升级/排除、缺项回落与未入组原因，而不是某个真实城市的权重。
 */
class LocalGrouperEngineTest {

    private static final UUID SET_ID = UUID.randomUUID();
    private final LocalGrouperEngine engine = new LocalGrouperEngine();

    private GrouperRuleSet ruleSet(String type) {
        return new GrouperRuleSet(SET_ID, "TEST-" + type, "1.0", "测试规则包", type,
                LocalDate.of(2026, 1, 1), "LOCAL", "checksum", GrouperRuleSet.STATUS_ACTIVE,
                "yb01", Instant.now(), "yb01", Instant.now());
    }

    private GrouperRuleSetBundle drgBundle() {
        List<GrouperMdcRule> mdc = List.of(
                new GrouperMdcRule(UUID.randomUUID(), SET_ID, 1, "MDCB", "呼吸系统",
                        "J00", "J99", true));
        List<GrouperAdrgRule> adrg = List.of(
                new GrouperAdrgRule(UUID.randomUUID(), SET_ID, 1, "ADRG-B1", "肺手术", "MDCB",
                        "PROCEDURE", null, "32", true),
                new GrouperAdrgRule(UUID.randomUUID(), SET_ID, 2, "ADRG-B2", "肺部感染", "MDCB",
                        "DIAGNOSIS", "J18", null, true));
        List<GrouperDrgDef> defs = List.of(
                def("DRG-B1A", "ADRG-B1", "NONE", "1.5000"),
                def("DRG-B1B", "ADRG-B1", "CC", "2.0000"),
                def("DRG-B1C", "ADRG-B1", "MCC", "3.0000"),
                def("DRG-B2A", "ADRG-B2", "NONE", "0.8000"),
                def("DRG-B2C", "ADRG-B2", "MCC", "2.2000"));
        List<GrouperCcRule> cc = List.of(
                new GrouperCcRule(UUID.randomUUID(), SET_ID, "MCC", "J96"),
                new GrouperCcRule(UUID.randomUUID(), SET_ID, "CC", "N18"));
        List<GrouperCcExclusion> exclusions = List.of(
                new GrouperCcExclusion(UUID.randomUUID(), SET_ID, "J18", "J96"));
        return new GrouperRuleSetBundle(mdc, adrg, defs, cc, exclusions, List.of());
    }

    private static GrouperDrgDef def(String drgCode, String adrgCode, String severity,
                                     String weight) {
        return new GrouperDrgDef(SET_ID, drgCode, drgCode, adrgCode, severity,
                new BigDecimal(weight));
    }

    @Test
    @DisplayName("手术命中 ADRG：无次要诊断进 NONE 细分组")
    void procedureWithoutComplication() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("J44.900", List.of(), List.of("32.2100")));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.mdcCode()).isEqualTo("MDCB");
        assertThat(outcome.adrgCode()).isEqualTo("ADRG-B1");
        assertThat(outcome.drgCode()).isEqualTo("DRG-B1A");
        assertThat(outcome.severity()).isEqualTo("NONE");
        assertThat(outcome.weight()).isEqualByComparingTo("1.5");
        assertThat(outcome.trace()).extracting(step -> step.get("step"))
                .containsExactly("MDC", "ADRG", "SEVERITY", "DRG");
    }

    @Test
    @DisplayName("MCC 升级：次要诊断命中 MCC 规则进高权重组")
    void mccEscalates() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("J44.900", List.of("J96.000"), List.of("32.2100")));

        assertThat(outcome.severity()).isEqualTo("MCC");
        assertThat(outcome.drgCode()).isEqualTo("DRG-B1C");
        assertThat(outcome.weight()).isEqualByComparingTo("3.0");
    }

    @Test
    @DisplayName("CC 升级：仅命中 CC 规则")
    void ccEscalates() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("J44.900", List.of("N18.500"), List.of("32.2100")));

        assertThat(outcome.severity()).isEqualTo("CC");
        assertThat(outcome.drgCode()).isEqualTo("DRG-B1B");
    }

    @Test
    @DisplayName("排除表：主诊断 J18 时 J96 不作为 MCC")
    void exclusionSuppressesMcc() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("J18.900", List.of("J96.000"), List.of()));

        assertThat(outcome.adrgCode()).isEqualTo("ADRG-B2");
        assertThat(outcome.severity()).isEqualTo("NONE");
        assertThat(outcome.drgCode()).isEqualTo("DRG-B2A");
        assertThat(outcome.trace().get(2).get("excluded").toString()).contains("J96.000");
    }

    @Test
    @DisplayName("缺 CC 细分组：回落到 NONE 并留回落轨迹（不猜相邻组）")
    void missingSeverityFallsBackToNone() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("J18.900", List.of("N18.500"), List.of()));

        assertThat(outcome.severity()).isEqualTo("NONE");
        assertThat(outcome.drgCode()).isEqualTo("DRG-B2A");
        assertThat(outcome.trace()).extracting(step -> step.get("step"))
                .contains("SEVERITY_FALLBACK");
    }

    @Test
    @DisplayName("主诊断不在 MDC 区间：显式未入组，不返回空分组")
    void mdcMissIsExplicit() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("K35.900", List.of(), List.of()));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.drgCode()).isNull();
        assertThat(outcome.reason()).contains("MDC");
    }

    @Test
    @DisplayName("MDC 命中但 ADRG 未命中：原因指向编码，而非笼统失败")
    void adrgMissIsExplicit() {
        GrouperOutcome outcome = engine.group(ruleSet("DRG"), drgBundle(),
                new GrouperInput("J44.900", List.of(), List.of()));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reason()).contains("ADRG");
    }

    @Test
    @DisplayName("DIP：诊断+主操作精确命中核心病种")
    void dipCoreWithProcedure() {
        GrouperOutcome outcome = engine.group(ruleSet("DIP"), dipBundle(),
                new GrouperInput("I50.900", List.of(), List.of("88.5600")));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.dipCode()).isEqualTo("DIP-A");
        assertThat(outcome.score()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("DIP：无手术时命中同诊断的无操作病种")
    void dipDiagOnly() {
        GrouperOutcome outcome = engine.group(ruleSet("DIP"), dipBundle(),
                new GrouperInput("I50.900", List.of(), List.of()));

        assertThat(outcome.dipCode()).isEqualTo("DIP-B");
        assertThat(outcome.score()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("DIP：综合病种取最长匹配（更具体优先）")
    void dipCompositeLongestPrefixWins() {
        GrouperOutcome outcome = engine.group(ruleSet("DIP"), dipBundle(),
                new GrouperInput("J18.901", List.of(), List.of()));

        assertThat(outcome.dipCode()).isEqualTo("DIP-D");
        assertThat(outcome.score()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("DIP：无病种匹配时显式未入组")
    void dipUnmatched() {
        GrouperOutcome outcome = engine.group(ruleSet("DIP"), dipBundle(),
                new GrouperInput("K35.900", List.of(), List.of()));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.reason()).contains("DIP");
    }

    private GrouperRuleSetBundle dipBundle() {
        List<GrouperDipCatalog> catalog = List.of(
                new GrouperDipCatalog(UUID.randomUUID(), SET_ID, "DIP-A", "心衰伴操作",
                        "I50.900", "88.5600", GrouperDipCatalog.CORE, new BigDecimal("100")),
                new GrouperDipCatalog(UUID.randomUUID(), SET_ID, "DIP-B", "心衰无操作",
                        "I50.900", null, GrouperDipCatalog.CORE, new BigDecimal("80")),
                new GrouperDipCatalog(UUID.randomUUID(), SET_ID, "DIP-C", "肺炎综合",
                        "J18", null, GrouperDipCatalog.COMPOSITE, new BigDecimal("60")),
                new GrouperDipCatalog(UUID.randomUUID(), SET_ID, "DIP-D", "肺炎细分",
                        "J18.9", null, GrouperDipCatalog.COMPOSITE, new BigDecimal("70")));
        return new GrouperRuleSetBundle(List.of(), List.of(), List.of(), List.of(), List.of(),
                catalog);
    }
}
