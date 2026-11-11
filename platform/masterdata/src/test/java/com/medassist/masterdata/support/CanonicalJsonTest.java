package com.medassist.masterdata.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规范化 JSON 与校验和：同一内容必须永远得到同一摘要（jsonb 不保留键顺序）。
 */
class CanonicalJsonTest {

    @Test
    @DisplayName("对象键顺序不同 → 规范化结果一致")
    void keyOrderDoesNotMatter() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "阿莫西林");
        first.put("code", "DRUG-001");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("code", "DRUG-001");
        second.put("name", "阿莫西林");

        assertThat(CanonicalJson.canonical(first)).isEqualTo(CanonicalJson.canonical(second));
        assertThat(CanonicalJson.checksum("prev", first))
                .isEqualTo(CanonicalJson.checksum("prev", second));
    }

    @Test
    @DisplayName("数值标度归一：1 / 1.0 / 1.00 视为同一值")
    void numbersNormalized() {
        assertThat(CanonicalJson.canonical(Map.of("price", new BigDecimal("1.00"))))
                .isEqualTo(CanonicalJson.canonical(Map.of("price", BigDecimal.ONE)));
        assertThat(CanonicalJson.canonical(Map.of("price", 4800.0)))
                .isEqualTo(CanonicalJson.canonical(Map.of("price", new BigDecimal("4800"))));
    }

    @Test
    @DisplayName("嵌套对象与数组：键排序递归生效，数组顺序保留")
    void nestedStructures() {
        Map<String, Object> a = Map.of("attributes", Map.of("b", 2, "a", 1));
        Map<String, Object> b = Map.of("attributes", Map.of("a", 1, "b", 2));
        assertThat(CanonicalJson.canonical(a)).isEqualTo(CanonicalJson.canonical(b));

        String list1 = CanonicalJson.canonical(Map.of("xs", List.of("a", "b")));
        String list2 = CanonicalJson.canonical(Map.of("xs", List.of("b", "a")));
        assertThat(list1).isNotEqualTo(list2);
    }

    @Test
    @DisplayName("校验和链：前一条不同 → 当前摘要不同")
    void checksumChains() {
        Map<String, Object> value = Map.of("name", "同一内容");
        assertThat(CanonicalJson.checksum("A", value))
                .isNotEqualTo(CanonicalJson.checksum("B", value));
        assertThat(CanonicalJson.checksum(null, value))
                .isEqualTo(CanonicalJson.checksum("", value));
    }
}
