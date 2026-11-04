package com.medassist.tech.client;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DICOM JSON（QIDO-RS）解析：标签取值、缺字段容错、脏数据拒绝。
 */
class PacsDicomJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("解析序列：UID/序号/设备/部位/描述/数量全部取到")
    void parsesSeriesFields() {
        String json = """
                [{"0020000E":{"vr":"UI","Value":["1.2.840.113619.2.55.3.1"]},
                  "00200011":{"vr":"IS","Value":[3]},
                  "00080060":{"vr":"CS","Value":["CT"]},
                  "00180015":{"vr":"CS","Value":["CHEST"]},
                  "0008103E":{"vr":"LO","Value":["胸部平扫"]},
                  "00201209":{"vr":"IS","Value":["120"]}}]
                """;

        List<PacsDicomGateway.SeriesPayload> series = PacsDicomJson.parseSeries(json, mapper);

        assertThat(series).hasSize(1);
        PacsDicomGateway.SeriesPayload s = series.get(0);
        assertThat(s.seriesInstanceUid()).isEqualTo("1.2.840.113619.2.55.3.1");
        assertThat(s.seriesNumber()).isEqualTo(3);
        assertThat(s.modality()).isEqualTo("CT");
        assertThat(s.bodyPart()).isEqualTo("CHEST");
        assertThat(s.description()).isEqualTo("胸部平扫");
        assertThat(s.instanceCount()).isEqualTo(120);
    }

    @Test
    @DisplayName("缺少 SeriesInstanceUID 的行跳过，不因单行脏数据整批失败")
    void skipsRowsWithoutSeriesUid() {
        String json = """
                [{"00080060":{"vr":"CS","Value":["CT"]}},
                 {"0020000E":{"vr":"UI","Value":["1.2.3"]},
                  "00080060":{"vr":"CS","Value":["MR"]}}]
                """;

        List<PacsDicomGateway.SeriesPayload> series = PacsDicomJson.parseSeries(json, mapper);

        assertThat(series).hasSize(1);
        assertThat(series.get(0).seriesInstanceUid()).isEqualTo("1.2.3");
        assertThat(series.get(0).modality()).isEqualTo("MR");
        // 缺 NumberOfSeriesRelatedInstances 时按 0 由上层处理，不在这里编造数值
        assertThat(series.get(0).instanceCount()).isNull();
    }

    @Test
    @DisplayName("Modality 缺失回落 OT（Other），避免序列在按设备过滤时消失")
    void defaultsModalityToOther() {
        String json = "[{\"0020000E\":{\"vr\":\"UI\",\"Value\":[\"1.2.3\"]}}]";

        assertThat(PacsDicomJson.parseSeries(json, mapper).get(0).modality()).isEqualTo("OT");
    }

    @Test
    @DisplayName("解析影像：SOP UID/序号/SOP Class 取到；缺 SOP 的行跳过")
    void parsesInstances() {
        String json = """
                [{"00080018":{"vr":"UI","Value":["1.2.3.4.1"]},
                  "00200013":{"vr":"IS","Value":[7]},
                  "00080016":{"vr":"UI","Value":["1.2.840.10008.5.1.4.1.1.2"]}},
                 {"00200013":{"vr":"IS","Value":[8]}},
                 {"00080018":{"vr":"UI","Value":["1.2.3.4.2"]}}]
                """;

        List<PacsDicomGateway.InstanceMeta> instances =
                PacsDicomJson.parseInstances(json, mapper);

        assertThat(instances).hasSize(2);
        assertThat(instances.get(0).sopInstanceUid()).isEqualTo("1.2.3.4.1");
        assertThat(instances.get(0).instanceNumber()).isEqualTo(7);
        assertThat(instances.get(0).sopClassUid()).isEqualTo("1.2.840.10008.5.1.4.1.1.2");
        // 缺序号的行保留（仍可调阅），不编造序号
        assertThat(instances.get(1).instanceNumber()).isNull();
    }

    @Test
    @DisplayName("影像列表：空响应 → 空列表；非 JSON → 46408")
    void rejectsMalformedInstances() {
        assertThat(PacsDicomJson.parseInstances("", mapper)).isEmpty();
        assertThatThrownBy(() -> PacsDicomJson.parseInstances("not-json", mapper))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_PACS_UPSTREAM_FAILED);
    }

    @Test
    @DisplayName("空响应 → 空列表；非 JSON / 非数组 → 46408")
    void rejectsMalformedPayload() {
        assertThat(PacsDicomJson.parseSeries("", mapper)).isEmpty();
        assertThat(PacsDicomJson.parseSeries(null, mapper)).isEmpty();

        assertThatThrownBy(() -> PacsDicomJson.parseSeries("not-json", mapper))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_PACS_UPSTREAM_FAILED);

        assertThatThrownBy(() -> PacsDicomJson.parseSeries("{\"a\":1}", mapper))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_PACS_UPSTREAM_FAILED);
    }
}
