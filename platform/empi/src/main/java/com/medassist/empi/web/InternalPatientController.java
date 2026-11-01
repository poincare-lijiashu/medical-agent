package com.medassist.empi.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.empi.domain.Patient;
import com.medassist.empi.repo.PatientRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间患者查询（仅内网直连，不经过网关）。
 *
 * <p>安全边界：
 * <ul>
 *   <li>网关对 {@code /api/v2/*&#47;internal/**} 直接 404，外部无法经网关访问；</li>
 *   <li>仍要求共享令牌 {@code X-Internal-Token}，令牌未配置时**拒绝所有请求**（fail-closed），
 *       避免"忘了配令牌"变成裸奔的内部接口；</li>
 *   <li>只返回最小字段（id/patientNo/name/status/birthDate/gender），证件号、手机号、住址一律不出域；</li>
 *   <li>{@code birthDate} 供用药安全规则判断儿童/老年人群（不做任何展示），仍属最小必要；</li>
 *   <li>{@code gender} 供病案首页与 HQMS 报文（省局必填项），同样属最小必要；</li>
 *   <li>恒定时间比较，避免令牌被逐字符探测。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/patients")
public class InternalPatientController {

    private final PatientRepository repo;
    private final String token;

    public InternalPatientController(PatientRepository repo,
                                     @Value("${empi.internal-token:}") String token) {
        this.repo = repo;
        this.token = token == null ? "" : token;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id,
                                   @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        // 统一泛化文案：不区分"未启用/令牌错误/令牌缺失"，避免外部探测内部接口状态
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
        if (provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
        Patient p = repo.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("patientNo", p.patientNo());
        body.put("name", p.name());
        body.put("status", p.status());
        // 用药安全规则需要年龄（儿童/老年禁忌），只返回出生日期，不返回证件号/联系方式
        body.put("birthDate", p.birthDate());
        // 病案首页/HQMS 报文必须带性别，属最小必要字段（不含证件号、联系方式、住址）
        body.put("gender", p.gender());
        return body;
    }
}
