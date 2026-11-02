package com.medassist.clinical.web;

import com.medassist.clinical.domain.OrderFrequency;
import com.medassist.clinical.web.ClinicalDtos.FrequencyView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 临床基础词表（只读）。
 *
 * <p>频次词表放在服务端而不是前端硬编码：频次决定"每日执行次数"，
 * 与长嘱按日计费的口径必须同源（评审 B2）。
 */
@RestController
@RequestMapping("/clinical")
public class ReferenceController {

    private static final String VISIT_READ = "clinical:visit:read";

    /** 医嘱频次词表：{@code daily=false} 的频次不允许用于长嘱。 */
    @GetMapping("/frequencies")
    public ApiResponse<List<FrequencyView>> frequencies(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        List<FrequencyView> views = OrderFrequency.all().stream()
                .map(f -> new FrequencyView(f.code(), f.label(), f.timesPerDay(), f.daily()))
                .toList();
        return ApiResponse.ok(views);
    }
}
