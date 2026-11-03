package com.medassist.tech.client;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * DICOM JSON（application/dicom+json）最小解析器。
 *
 * <p>QIDO-RS 的响应是"标签 → {vr, Value}"的对象数组，例如：
 * <pre>
 * [{"0020000E":{"vr":"UI","Value":["1.2.3"]},
 *   "00200011":{"vr":"IS","Value":[3]},
 *   "00080060":{"vr":"CS","Value":["CT"]},
 *   "00201209":{"vr":"IS","Value":[120]}}]
 * </pre>
 * 只取阅片台用得到的 6 个标签；缺标签按空处理，不因单个字段缺失丢弃整条序列。
 */
final class PacsDicomJson {

    static final String TAG_SERIES_UID = "0020000E";
    static final String TAG_SERIES_NUMBER = "00200011";
    static final String TAG_MODALITY = "00080060";
    static final String TAG_BODY_PART = "00180015";
    static final String TAG_DESCRIPTION = "0008103E";
    static final String TAG_INSTANCE_COUNT = "00201209";

    private PacsDicomJson() {
    }

    static List<PacsDicomGateway.SeriesPayload> parseSeries(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED,
                    "影像服务返回的序列元数据不是合法 JSON");
        }
        if (root == null || !root.isArray()) {
            throw new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED,
                    "影像服务返回的序列元数据格式不正确");
        }
        List<PacsDicomGateway.SeriesPayload> result = new ArrayList<>();
        for (JsonNode item : root) {
            String uid = text(item, TAG_SERIES_UID);
            if (uid == null) {
                // 没有 SeriesInstanceUID 的行无法调阅，跳过而不是整批失败
                continue;
            }
            String modality = text(item, TAG_MODALITY);
            result.add(new PacsDicomGateway.SeriesPayload(
                    uid,
                    integer(item, TAG_SERIES_NUMBER),
                    modality == null ? "OT" : modality,
                    text(item, TAG_BODY_PART),
                    text(item, TAG_DESCRIPTION),
                    integer(item, TAG_INSTANCE_COUNT)));
        }
        return result;
    }

    private static JsonNode firstValue(JsonNode item, String tag) {
        JsonNode tagNode = item.get(tag);
        if (tagNode == null) {
            return null;
        }
        JsonNode values = tagNode.get("Value");
        if (values == null || !values.isArray() || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static String text(JsonNode item, String tag) {
        JsonNode first = firstValue(item, tag);
        if (first == null || first.isNull()) {
            return null;
        }
        String value = first.isString() ? first.asString() : first.toString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer integer(JsonNode item, String tag) {
        String value = text(item, tag);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
