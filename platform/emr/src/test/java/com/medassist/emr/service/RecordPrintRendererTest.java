package com.medassist.emr.service;

import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打印视图：必须转义所有病历文本（防 XSS），并展示当前版签名与内容摘要。
 */
class RecordPrintRendererTest {

    private MedicalRecord record(String text) {
        return record(text, MedicalRecord.ARCHIVED);
    }

    private MedicalRecord record(String text, String status) {
        return new MedicalRecord(UUID.randomUUID(), "MR20261031000001", UUID.randomUUID(),
                "P20261031000001", null, null, null, "ADMISSION_NOTE", "TPL-ADMISSION",
                "入院记录", "{}", text, "doctor01", status, 1,
                "chief01", Instant.parse("2026-10-31T02:00:00Z"),
                RecordService.sha256(text), Instant.now(), 1L, Instant.now());
    }

    @Test
    @DisplayName("打印视图：未归档文书带对角线水印，已归档不带")
    void watermarkOnlyForUnarchived() {
        assertThat(RecordPrintRenderer.render(record("主诉：发热\n", MedicalRecord.SIGNED), List.of()))
                .contains("未归档 · NOT ARCHIVED");
        assertThat(RecordPrintRenderer.render(record("主诉：发热\n"), List.of()))
                .doesNotContain("NOT ARCHIVED");
    }

    @Test
    @DisplayName("打印视图：正文与标题中的脚本标签被转义")
    void escapesUserContent() {
        String html = RecordPrintRenderer.render(
                record("<script>alert('x')</script>主诉：<b>发热</b>"), List.of());

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&lt;b&gt;发热&lt;/b&gt;");
    }

    @Test
    @DisplayName("打印视图：展示文书类型、版本、状态、签名人与内容摘要")
    void showsMetadataAndSignature() {
        MedicalRecord rec = record("主诉：发热\n");
        RecordSignature sig = new RecordSignature(UUID.randomUUID(), rec.id(), 1,
                "chief01", RecordSignature.CHIEF, rec.signedAt(), rec.contentHash());

        String html = RecordPrintRenderer.render(rec, List.of(sig));

        assertThat(html).contains("入院记录");
        assertThat(html).contains("第 1 版");
        assertThat(html).contains("已归档");
        assertThat(html).contains("主任医师签名：chief01");
        assertThat(html).contains(rec.contentHash());
        assertThat(html).contains("打印 → 另存为 PDF");
    }
}
