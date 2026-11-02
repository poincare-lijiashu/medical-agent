package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.repo.ClinicalRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 临床基础词表服务（只读）。
 *
 * <p>与 {@code ReferenceController} 配套：科室、收费项目都来自主数据表，
 * 前端只做"选择"，价格与启用状态一律以服务端返回为准（避免前端缓存旧价）。
 */
@Service
public class ReferenceService {

    private final ClinicalRepository repo;

    public ReferenceService(ClinicalRepository repo) {
        this.repo = repo;
    }

    public List<Dept> listDepartments() {
        return repo.listDepartments();
    }

    public List<ChargeItemDef> searchChargeItems(String keyword, String category, int limit) {
        return repo.searchChargeItems(keyword, category, limit);
    }
}
