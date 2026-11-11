package com.medassist.clinical.service;

import com.medassist.clinical.domain.NursingSheetModel.SheetLine;
import com.medassist.clinical.domain.NursingSheetModel.SheetPatient;
import com.medassist.clinical.domain.NursingSheetModel.WardSheets;
import com.medassist.clinical.repo.NursingSheetRepository;
import com.medassist.clinical.repo.NursingSheetRepository.SheetRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 整区护理执行单聚合（第 234 批，第 240 批加分批）。
 *
 * <p>把“一行一条医嘱”的扁平结果按住院分组，保持 SQL 的床位顺序；
 * 当日没有有效长期医嘱的患者不出现在 {@code sheets} 里（避免打印空白页），
 * 但 {@code patientCount} 仍返回在院总数，前端可据此提示“本区 N 人、M 人有长嘱”。
 *
 * <p>分批口径：一次最多 {@link #BATCH_MAX} 位患者。上限不是性能参数而是**保护**——
 * 床位数据异常（如误配 300 张床）时，一次性渲染几百页 HTML 会让浏览器打印预览卡死，
 * 也会在留痕表里刷出几百条记录。服务端夹取而不报错：护士要的是出纸，不是看 422。
 */
@Service
public class NursingSheetService {

    /** 单批患者上限：正常病区 30–60 床，80 留余量。 */
    public static final int BATCH_MAX = 80;

    private final NursingSheetRepository repo;

    public NursingSheetService(NursingSheetRepository repo) {
        this.repo = repo;
    }

    public WardSheets wardSheets(UUID wardId, LocalDate date) {
        return wardSheets(wardId, date, null, null);
    }

    public WardSheets wardSheets(UUID wardId, LocalDate date, Integer offset, Integer limit) {
        LocalDate day = date == null ? LocalDate.now() : date;
        int size = limit == null ? BATCH_MAX : Math.max(1, Math.min(limit, BATCH_MAX));
        int start = offset == null ? 0 : Math.max(0, offset);
        int patientCount = repo.countActiveAdmissions(wardId);
        int sheetPatients = repo.countSheetPatients(wardId, day);
        Map<UUID, PatientAcc> acc = new LinkedHashMap<>();
        for (SheetRow row : repo.listRows(wardId, day, size, start)) {
            PatientAcc p = acc.computeIfAbsent(row.admissionId(),
                    id -> new PatientAcc(row.admissionId(), row.admissionNo(), row.patientNo(),
                            row.visitId(), row.bedNo(), row.attendingDoctor()));
            p.lines.add(new SheetLine(row.orderId(), row.orderNo(), row.itemName(), row.dose(),
                    row.frequency(), row.execStatus(), row.executor(), row.note()));
        }
        List<SheetPatient> sheets = new ArrayList<>(acc.size());
        for (PatientAcc p : acc.values()) {
            sheets.add(new SheetPatient(p.admissionId, p.admissionNo, p.patientNo, p.visitId,
                    p.bedNo, p.attendingDoctor, List.copyOf(p.lines)));
        }
        boolean hasMore = start + sheets.size() < sheetPatients;
        return new WardSheets(wardId, day, patientCount, sheetPatients, start, size, hasMore, sheets);
    }

    /** 分组累加器：同一住院的多条医嘱合并到一张执行单。 */
    private static final class PatientAcc {
        private final UUID admissionId;
        private final String admissionNo;
        private final String patientNo;
        private final UUID visitId;
        private final String bedNo;
        private final String attendingDoctor;
        private final List<SheetLine> lines = new ArrayList<>();

        private PatientAcc(UUID admissionId, String admissionNo, String patientNo, UUID visitId,
                           String bedNo, String attendingDoctor) {
            this.admissionId = admissionId;
            this.admissionNo = admissionNo;
            this.patientNo = patientNo;
            this.visitId = visitId;
            this.bedNo = bedNo;
            this.attendingDoctor = attendingDoctor;
        }
    }
}
