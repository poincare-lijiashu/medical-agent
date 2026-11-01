package com.medassist.kb.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 中文二元组词元与 tsquery 构造。 */
class TextTermsTest {

    @Test
    @DisplayName("中文按二元组切分：阑尾炎 → 阑尾 尾炎")
    void chineseBigrams() {
        assertThat(TextTerms.toTerms("阑尾炎")).isEqualTo("阑尾 尾炎");
    }

    @Test
    @DisplayName("单字成词：只有一个字时保留本身，否则这个词永远搜不到")
    void singleCharKept() {
        assertThat(TextTerms.toTerms("疸")).isEqualTo("疸");
    }

    @Test
    @DisplayName("英文/数字按词切分并小写，标点作为分隔符")
    void latinAndDigits() {
        assertThat(TextTerms.toTerms("CT 检查，WBC 12.5")).contains("ct").contains("wbc").contains("12");
    }

    @Test
    @DisplayName("查询词元用 OR 连接，长查询不会因为 AND 变空集")
    void tsQueryUsesOr() {
        String tsQuery = TextTerms.toTsQuery("阑尾炎伴穿孔");
        assertThat(tsQuery).contains(" | ");
        assertThat(tsQuery).doesNotContain("&");
    }

    @Test
    @DisplayName("纯标点查询返回空串（由上层判定为 422，而不是去查全表）")
    void punctuationOnlyReturnsBlank() {
        assertThat(TextTerms.toTsQuery("，。！  ")).isEmpty();
    }

    @Test
    @DisplayName("词元去重且保序，避免 tsquery 里出现重复项")
    void dedupesTerms() {
        assertThat(TextTerms.toTerms("肺炎肺炎")).isEqualTo("肺炎 炎肺");
    }
}
