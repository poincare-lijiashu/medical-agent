package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.ExamItemView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 医技项目字典查询（只读；字典由院内物价/LIS/PACS 同步，M1 阶段不提供在线维护）。
 */
@RestController
@RequestMapping("/tech/items")
public class TechCatalogController {

    private static final String READ = "tech:read";

    private final TechRepository repo;

    public TechCatalogController(TechRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ApiResponse<List<ExamItemView>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        String normalized = (category == null || category.isBlank()) ? null : category.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return ApiResponse.ok(repo.listExamItems(normalized, keyword, size).stream()
                .map(ExamItemView::new).toList());
    }
}
