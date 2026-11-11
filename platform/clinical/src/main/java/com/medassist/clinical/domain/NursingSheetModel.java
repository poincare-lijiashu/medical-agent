package com.medassist.clinical.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 整区护理执行单（第 234 批）。
 *
 * <p>为“一次出全病区执行单”提供批量取数，避免前端按床位逐人请求
 * （30 床 = 60 次往返）。口径与单患者执行页一致：
 * 长期医嘱（LONG）+ 在途（PLACED）+ 执行日落在 start/stop 窗口内；
 * 执行状态取“有效状态”（已更正取更正后状态）。
 *
 * <p>只做只读聚合，不写任何表；打印留痕仍由前端在出纸后上报。
 */
public final class NursingSheetModel {

    private NursingSheetModel() {
    }

    /** 单条医嘱 + 当日执行状态（null 表示未记录）。 */
    public record SheetLine(UUID orderId, String orderNo, String itemName, String dose,
                            String frequency, String execStatus, String executor, String note) {
    }

    /** 一位在院患者的执行单（床位排序由查询保证）。 */
    public record SheetPatient(UUID admissionId, String admissionNo, String patientNo, UUID visitId,
                               String bedNo, String attendingDoctor, List<SheetLine> lines) {
    }

    /** 整区结果：patientCount 为在院患者总数，sheets 只含当日有有效长嘱的床位。 */
    public record WardSheets(UUID wardId, LocalDate date, int patientCount,
                             List<SheetPatient> sheets) {
    }
}
