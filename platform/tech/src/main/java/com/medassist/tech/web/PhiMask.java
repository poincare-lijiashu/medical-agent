package com.medassist.tech.web;

import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.WorklistItem;

import java.util.List;

/**
 * 医技侧 PHI 掩码（P0-S2）：没有 {@code phi:read} 时把患者号替换为掩码后再返回。
 *
 * <p>为什么掩码在服务端做：前端拿到全量再遮，数据已经出库并留在浏览器内存与日志里，
 * 脱敏就只剩视觉效果。这里在 Controller 出口统一改写，前端无需任何改动。
 *
 * <p>此前 tech 直接返回患者号明文，{@code phi:read} 体系在本域失效，
 * QC / REGISTRAR / TECHNICIAN 等低权账号可取明文患者号。
 */
final class PhiMask {

    private PhiMask() {
    }

    static Application application(Application a, DataScope scope) {
        if (a == null || scope.phiReadable()) {
            return a;
        }
        return new Application(a.id(), a.applicationNo(), a.patientId(),
                scope.maskPatientNo(a.patientNo()), a.visitId(), a.visitNo(), a.deptId(),
                a.appliedBy(), a.category(), a.itemCode(), a.itemName(), a.bodyPart(),
                a.priority(), a.clinicalDiag(), a.specimenType(), a.status(), a.version(),
                a.createdAt(), a.sourceOrderId());
    }

    static List<Application> applications(List<Application> list, DataScope scope) {
        if (scope.phiReadable()) {
            return list;
        }
        return list.stream().map(a -> application(a, scope)).toList();
    }

    static WorklistItem worklist(WorklistItem w, DataScope scope) {
        if (w == null || scope.phiReadable()) {
            return w;
        }
        return new WorklistItem(w.applicationId(), w.applicationNo(),
                scope.maskPatientNo(w.patientNo()), w.itemCode(), w.itemName(), w.bodyPart(),
                w.priority(), w.applicationStatus(), w.scheduledAt(), w.modality(), w.studyId(),
                w.studyInstanceUid(), w.studyStatus(), w.seriesCount(), w.imageCount(), w.viewerUrl());
    }

    static List<WorklistItem> worklist(List<WorklistItem> list, DataScope scope) {
        if (scope.phiReadable()) {
            return list;
        }
        return list.stream().map(w -> worklist(w, scope)).toList();
    }
}
