package com.medassist.empi.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 患者资料修改请求（version 用于乐观锁；证件号留空表示不变更）。 */
public record UpdatePatientReq(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过 64")
        String name,

        @Pattern(regexp = "MALE|FEMALE|UNKNOWN", message = "性别只能是 MALE / FEMALE / UNKNOWN")
        String gender,

        LocalDate birthDate,

        @Pattern(regexp = "ID_CARD|PASSPORT|MILITARY|HK_MACAO|NONE", message = "证件类型不合法")
        String idType,

        @Size(max = 64, message = "证件号长度不能超过 64")
        String idNo,

        @Size(max = 32, message = "手机号长度不能超过 32")
        String phone,

        @Size(max = 256, message = "地址长度不能超过 256")
        String address,

        @Size(max = 16, message = "血型长度不能超过 16")
        String bloodType,

        @Size(max = 512, message = "过敏史长度不能超过 512")
        String allergy,

        @NotNull(message = "版本号不能为空（用于并发控制）")
        Long version
) {
}
