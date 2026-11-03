package com.medassist.common.security;

/**
 * 敏感字段掩码（P1-S4）。
 *
 * <p>为什么单独抽出来：权限层的 {@link DataScope} 只解决"响应体要不要脱敏"，
 * 但**审计明细**是另一条出口——它落库且长期留存，此前多处把患者号/姓名原样写进
 * {@code detail}，等于在审计表里复制了一份 PHI。审计只需要能定位对象（target_id 已是
 * 业务主键），不需要明文身份信息，因此统一走这里。
 *
 * <p>算法与 {@link DataScope} 的响应脱敏保持一致，避免"响应里是 {@code 123****89}、
 * 审计里是原文"的口径分裂。
 */
public final class Masks {

    private Masks() {
    }

    /**
     * 患者号掩码：保留前 3 后 2，中间用 {@code *} 补足。
     *
     * <p>长度不足 6 的一律全掩码——短编号保留前后位等于直接暴露。
     */
    public static String patientNo(String patientNo) {
        if (patientNo == null || patientNo.isBlank()) {
            return patientNo;
        }
        String value = patientNo.trim();
        if (value.length() < 6) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 3)
                + "*".repeat(Math.max(2, value.length() - 5))
                + value.substring(value.length() - 2);
    }

    /** 姓名掩码：只留首字，其余用 {@code *}。单字姓名整体掩码。 */
    public static String patientName(String patientName) {
        if (patientName == null || patientName.isBlank()) {
            return patientName;
        }
        String value = patientName.trim();
        if (value.length() <= 1) {
            return "*";
        }
        return value.charAt(0) + "*".repeat(value.length() - 1);
    }
}
