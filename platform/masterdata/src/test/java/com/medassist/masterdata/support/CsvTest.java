package com.medassist.masterdata.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 解析：引号、逗号、换行与 BOM。
 */
class CsvTest {

    @Test
    @DisplayName("普通行与 CRLF")
    void plainRows() {
        List<List<String>> rows = Csv.parse("a,b,c\r\n1,2,3\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("a", "b", "c");
        assertThat(rows.get(1)).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("引号包裹的逗号、换行与双引号")
    void quotedValues() {
        String csv = "code,name\r\nX1,\"含,逗号\"\r\nX2,\"第一行\n第二行\"\r\nX3,\"引号\"\"转义\"\r\n";
        List<List<String>> rows = Csv.parse(csv);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(1).get(1)).isEqualTo("含,逗号");
        assertThat(rows.get(2).get(1)).isEqualTo("第一行\n第二行");
        assertThat(rows.get(3).get(1)).isEqualTo("引号\"转义");
    }

    @Test
    @DisplayName("导出：BOM + 必要转义，空行被跳过")
    void writeAndParseRoundTrip() {
        String csv = Csv.write(List.of("域编码", "本地编码"),
                List.of(List.of("DEPT", "D1"), List.of("DRUG", "含,逗号")));
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("\"含,逗号\"");
        List<List<String>> rows = Csv.parse(csv);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("域编码", "本地编码");

        assertThat(Csv.parse("a,b\r\n\r\n \r\n")).hasSize(1);
    }
}
