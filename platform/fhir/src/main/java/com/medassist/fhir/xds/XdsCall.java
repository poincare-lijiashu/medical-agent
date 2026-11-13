package com.medassist.fhir.xds;

import java.util.Set;
import java.util.UUID;

/**
 * 一次 XDS 调用的上下文（身份、院区范围、PHI 口径）。
 *
 * <p>与 FHIR 的 {@code FhirCall} 分开：XDS 的授权点是 {@code fhir:xds:*}，
 * 患者明文口径是 {@code fhir:xds:phi}，不与 FHIR 资源读取权限混用
 * （避免"能读 FHIR 资源"被误解成"能调阅共享文档"）。
 */
public record XdsCall(String actor, String permissions, boolean phiReadable,
                      Set<UUID> campusFilter, String clientIp, String traceId) {

    /** 院区收敛：空集合表示 {@code X-Campus: *}（显式全院区账号）。 */
    public boolean canAccess(UUID campusId) {
        return campusFilter == null || campusFilter.isEmpty()
                || (campusId != null && campusFilter.contains(campusId));
    }
}
