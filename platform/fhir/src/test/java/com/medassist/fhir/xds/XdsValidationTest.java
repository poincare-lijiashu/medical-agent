package com.medassist.fhir.xds;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** XDS 入参校验：唯一标识、时间、哈希与安全序列化。 */
class XdsValidationTest {

    @Test
    @DisplayName("SHA-256 小写十六进制且大小写不敏感比较")
    void sha256() {
        String hash = XdsValidation.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(hash).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(XdsValidation.hashEquals(hash, hash.toUpperCase())).isTrue();
        assertThat(XdsValidation.hashEquals(hash, "0".repeat(64))).isFalse();
    }

    @Test
    @DisplayName("uniqueId 接受 OID 与 urn，拒绝空格/路径字符/过短")
    void uniqueId() {
        assertThat(XdsValidation.requireUniqueId("urn:uuid:11111111-1111-1111-1111-111111111111",
                "uniqueId")).startsWith("urn:uuid:");
        assertThat(XdsValidation.requireUniqueId("1.2.156.112.1.1.31", "uniqueId"))
                .isEqualTo("1.2.156.112.1.1.31");
        for (String bad : List.of("ab", "urn:uuid:has space", "../etc/passwd", "a/b")) {
            assertThatThrownBy(() -> XdsValidation.requireUniqueId(bad, "uniqueId"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode())
                                    .isEqualTo(ErrorCode.XDS_METADATA_INVALID));
        }
    }

    @Test
    @DisplayName("时间必须带时区，避免把本地时间当 UTC")
    void instant() {
        assertThat(XdsValidation.requireInstant("2026-11-13T09:30:00+08:00", "creationTime"))
                .isEqualTo(Instant.parse("2026-11-13T01:30:00Z"));
        assertThatThrownBy(() -> XdsValidation.requireInstant("2026-11-13T09:30:00",
                "creationTime"))
                .isInstanceOf(BusinessException.class);
        assertThat(XdsValidation.parseQueryInstant("2026-11-13", "from"))
                .isEqualTo(Instant.parse("2026-11-13T00:00:00Z"));
    }

    @Test
    @DisplayName("事件码 JSON 往返稳定，异常格式不抛错")
    void eventCodes() {
        List<String> codes = List.of("LAB", "DISCHARGE");
        String json = XdsValidation.toJsonArray(codes);
        assertThat(json).isEqualTo("[\"LAB\",\"DISCHARGE\"]");
        assertThat(XdsValidation.parseJsonArray(json)).containsExactlyElementsOf(codes);
        assertThat(XdsValidation.parseJsonArray("not-json")).isEmpty();
    }
}
