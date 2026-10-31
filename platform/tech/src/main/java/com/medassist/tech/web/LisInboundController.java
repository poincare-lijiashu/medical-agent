package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.tech.service.LisInboundService;
import com.medassist.tech.web.TechDtos.OruResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LIS 入站接口（HL7 v2 ORU^R01）。
 *
 * <p>权限：{@code tech:lis:write}（接口账号 / 技师）；报文以纯文本提交，
 * 分隔符从 MSH 段读取，不假设固定编码。
 *
 * <p>返回 200 表示**消息已被接收并留档**（status 可能是 PROCESSED/IGNORED/FAILED），
 * 只有报文不可解析（缺控制 ID/申请号/OBX）才返回 422 且不留库。
 */
@RestController
@RequestMapping("/tech/lis")
public class LisInboundController {

    private static final String WRITE = "tech:lis:write";

    private final LisInboundService service;

    public LisInboundController(LisInboundService service) {
        this.service = service;
    }

    @PostMapping(value = "/oru", consumes = {MediaType.TEXT_PLAIN_VALUE, "application/hl7-v2"})
    public ApiResponse<OruResult> oru(
            @RequestBody String message,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.ingest(message));
    }
}
