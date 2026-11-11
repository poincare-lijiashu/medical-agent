package com.medassist.migration.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 解析：BOM、引号、单元格内换行是医院导出文件最常见的三种坑。
 */
class CsvTest {

    @Test
    @DisplayName("解析：去掉 BOM、支持引号内逗号/换行/双引号转义")
    void parseQuotedAndBom() {
        String csv = "\uFEFF姓名,备注\r\n"
                + "张三,\"发热,咳嗽\"\r\n"
                + "李四,\"第一行\n第二行\"\r\n"
                + "王五,\"他说\"\"没事\"\"\"\r\n";
        List<Map<String, String>> rows = Csv.parse(csv);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("姓名", "张三").containsEntry("备注", "发热,咳嗽");
        assertThat(rows.get(1).get("备注")).isEqualTo("第一行\n第二行");
        assertThat(rows.get(2).get("备注")).isEqualTo("他说\"没事\"");
    }

    @Test
    @DisplayName("解析：缺列按空串补齐，空行跳过")
    void parseShortRows() {
        String csv = "a,b,c\n1,2\n\n3,4,5\n";
        List<Map<String, String>> rows = Csv.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("c", "");
        assertThat(rows.get(1)).containsEntry("c", "5");
    }

    @Test
    @DisplayName("导出：带 BOM 与 CRLF，含逗号/引号的值正确转义")
    void writeRoundTrip() {
        String text = Csv.write(List.of("name", "memo"),
                List.of(Map.of("name", "张三", "memo", "a,b\"c")));

        assertThat(text).startsWith("\uFEFFname,memo\r\n");
        assertThat(Csv.parse(text)).containsExactly(Map.of("name", "张三", "memo", "a,b\"c"));
    }

    @Test
    @DisplayName("表头：返回首行，去掉首尾空白")
    void header() {
        assertThat(Csv.header(" a , b ,c\n1,2,3")).containsExactly("a", "b", "c");
    }
}
