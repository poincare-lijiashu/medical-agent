package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.OrderFrequency;
import com.medassist.clinical.service.ReferenceService;
import com.medassist.clinical.web.ClinicalDtos.FrequencyView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private final ReferenceService service;

    public ReferenceController(ReferenceService service) {
        this.service = service;
    }

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

    /** 启用科室列表（挂号/开嘱的科室选择）。 */
    @GetMapping("/departments")
    public ApiResponse<List<Dept>> departments(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.listDepartments());
    }

    /**
     * 收费项目检索（医嘱开立选项目）。
     *
     * <p>返回的 {@code price} 仅用于展示；下单只传 {@code itemCode}，
     * 服务端会重新读主数据计价。
     */
    @GetMapping("/charge-items")
    public ApiResponse<List<ChargeItemDef>> chargeItems(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.searchChargeItems(q, category, limit));
    }
}
