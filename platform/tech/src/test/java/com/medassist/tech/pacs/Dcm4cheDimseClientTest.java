package com.medassist.tech.pacs;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DICOM 查询键与响应解析的纯单测（不连 PACS）。
 *
 * <p>这两段最容易出错且最难在联调时定位：键拼错会导致"查询成功但没有结果"，
 * 结果解析错会导致前端显示空患者/空序列。
 */
class Dcm4cheDimseClientTest {

    @Test
    @DisplayName("Study C-FIND：条件转查询键，返回键设为空串")
    void studyKeys() {
        Attributes keys = Dcm4cheDimseClient.studyKeys(new PacsDimseGateway.StudyQuery(
                "P001", "ZHANG^SAN", "A20261111001",
                "20261101", "20261130", "CT"));

        assertThat(keys.getString(Tag.QueryRetrieveLevel)).isEqualTo("STUDY");
        assertThat(keys.getString(Tag.PatientID)).isEqualTo("P001");
        assertThat(keys.getString(Tag.PatientName)).isEqualTo("ZHANG^SAN");
        assertThat(keys.getString(Tag.AccessionNumber)).isEqualTo("A20261111001");
        assertThat(keys.getString(Tag.StudyDate)).isEqualTo("20261101-20261130");
        assertThat(keys.getString(Tag.ModalitiesInStudy)).isEqualTo("CT");
        // 返回键以零长度值出现：dcm4che 对空 UI/IS 的 getString 返回 null，但键必须存在
        assertThat(keys.contains(Tag.StudyInstanceUID)).isTrue();
        assertThat(keys.getString(Tag.StudyInstanceUID)).isNullOrEmpty();
        assertThat(keys.contains(Tag.NumberOfStudyRelatedInstances)).isTrue();
        assertThat(keys.getString(Tag.NumberOfStudyRelatedInstances)).isNullOrEmpty();
    }

    @Test
    @DisplayName("Study C-FIND：空条件不写入，日期只有一端时另一端留空")
    void studyKeysWithPartialDate() {
        Attributes keys = Dcm4cheDimseClient.studyKeys(new PacsDimseGateway.StudyQuery(
                null, null, null, "20261101", null, null));

        assertThat(keys.contains(Tag.PatientID)).isFalse();
        assertThat(keys.contains(Tag.PatientName)).isFalse();
        assertThat(keys.getString(Tag.StudyDate)).isEqualTo("20261101-");
    }

    @Test
    @DisplayName("Study 结果：PN 还原空格，多值设备合并，计数缺失为 null")
    void parseStudy() {
        Attributes dataset = new Attributes();
        dataset.setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.1");
        dataset.setString(Tag.PatientID, VR.LO, "P001");
        dataset.setString(Tag.PatientName, VR.PN, "ZHANG^SAN");
        dataset.setString(Tag.AccessionNumber, VR.SH, "A1");
        dataset.setString(Tag.StudyDate, VR.DA, "20261111");
        dataset.setString(Tag.ModalitiesInStudy, VR.CS, "CT\\MR");
        dataset.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, 3);

        PacsDimseGateway.StudyResult result = Dcm4cheDimseClient.toStudy(dataset);

        assertThat(result.studyInstanceUid()).isEqualTo("1.2.840.1");
        assertThat(result.patientName()).isEqualTo("ZHANG SAN");
        assertThat(result.modalities()).isEqualTo("CT\\MR");
        assertThat(result.seriesCount()).isEqualTo(3);
        assertThat(result.instanceCount()).isNull();
    }

    @Test
    @DisplayName("Series 结果：UID/设备/序号/数量解析")
    void parseSeries() {
        Attributes dataset = new Attributes();
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.1.2");
        dataset.setString(Tag.Modality, VR.CS, "CT");
        dataset.setInt(Tag.SeriesNumber, VR.IS, 2);
        dataset.setString(Tag.SeriesDescription, VR.LO, "胸部平扫");
        dataset.setInt(Tag.NumberOfSeriesRelatedInstances, VR.IS, 120);

        PacsDimseGateway.SeriesResult result = Dcm4cheDimseClient.toSeries(dataset);

        assertThat(result.seriesInstanceUid()).isEqualTo("1.2.840.1.2");
        assertThat(result.modality()).isEqualTo("CT");
        assertThat(result.seriesNumber()).isEqualTo(2);
        assertThat(result.description()).isEqualTo("胸部平扫");
        assertThat(result.instanceCount()).isEqualTo(120);
    }
}
