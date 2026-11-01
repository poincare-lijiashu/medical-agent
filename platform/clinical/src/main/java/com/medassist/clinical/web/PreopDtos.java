package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PreopDiscussion;
import com.medassist.clinical.domain.ClinicalModel.SurgeryConsent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 术前讨论与知情同意请求/响应体。 */
public final class PreopDtos {

    private PreopDtos() {
    }

    /** 术前讨论（首次保存不带 version；再次保存必须带 version 做 CAS）。 */
    public record PreopReq(
            Instant discussionAt,
            @Size(max = 64) String moderator,
            @Size(max = 500) String participants,
            @Size(max = 2000) String diagnosisSummary,
            @Size(max = 2000) String procedurePlan,
            @Size(max = 2000) String risks,
            @Size(max = 1000) String anesthesiaPlan,
            @Size(max = 500) String bloodPreparation,
            Long version) {
    }

    /** 知情同意书（插入即已签署事实；写错只能作废后重签）。 */
    public record ConsentReq(
            @NotBlank @Pattern(regexp = "SURGERY|ANESTHESIA|BLOOD_TRANSFUSION|SPECIAL",
                    message = "同意书类型不合法") String consentType,
            @NotBlank @Size(max = 64) String signerName,
            @Size(max = 32) String signerRelation,
            Instant signedAt,
            @Size(max = 64) String witness,
            @Size(max = 500) String notes) {
    }

    /** 作废同意书：必须写原因。 */
    public record RevokeConsentReq(
            @NotNull Long version,
            @NotBlank @Size(max = 256) String reason) {
    }

    /**
     * 术前核查清单。
     *
     * @param missingConsents 缺失的有效同意书类型（手术 + 麻醉是硬门槛）
     * @param readyForSurgery 是否满足开台条件（计划态 + 同意书齐全）
     */
    public record PreopSummary(UUID caseId, String caseStatus, PreopDiscussion discussion,
                               List<SurgeryConsent> consents, List<String> missingConsents,
                               boolean readyForSurgery) {
    }
}
