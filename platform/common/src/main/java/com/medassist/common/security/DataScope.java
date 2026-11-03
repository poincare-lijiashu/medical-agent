package com.medassist.common.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.util.Set;
import java.util.UUID;

/**
 * 行级数据范围（谁可以看到哪些科室的数据、能否看到完整患者标识）。
 *
 * <p>规则来源见 {@code docs/DESIGN-DATA-SCOPE.md}，核心是两条：
 * <ol>
 *   <li><b>fail-closed</b>：科室头缺失或不合法时拒绝，绝不"不确定就放开"；</li>
 *   <li><b>全院可见是显式的</b>：只有药师/麻醉/手术室/质控/住院处/管理员这些
 *       业务上必须跨科室的角色才放开，普通医师默认只看本科室。</li>
 * </ol>
 *
 * <p>为什么不用 ThreadLocal 持有：WebFlux 与线程池下容易泄漏，测试还要手工清理。
 * 这里作为显式参数在 Controller → Service → Repository 之间传递：
 * 多一个参数，换来"看方法签名就知道这个查询受不受权限约束"。
 *
 * @param actor           操作者登录名（审计用）
 * @param deptCodes       可见科室；{@code allDepartments=true} 时为空集
 * @param allDepartments  是否全院可见
 * @param phiReadable     是否可读完整患者标识（否则按掩码返回）
 */
public record DataScope(String actor, Set<UUID> deptCodes, boolean allDepartments,
                        boolean phiReadable) {

    /** 业务上必须跨科室的角色：药师审方、麻醉访视、手术室排台、质控抽样、住院处办理、管理员。 */
    private static final Set<String> ALL_DEPARTMENT_ROLES = Set.of(
            "ADMIN", "QC", "REGISTRAR", "PHARMACIST", "ANESTHETIST", "OR_NURSE");

    /** 可读完整患者标识的权限点。 */
    public static final String PHI_READ = "phi:read";

    public DataScope {
        deptCodes = deptCodes == null ? Set.of() : Set.copyOf(deptCodes);
        // 单科室范围却没给科室 = 权限配置错误，必须拒绝而不是退化成全院
        if (!allDepartments && deptCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "当前账号未绑定科室，无法确定数据范围，请联系管理员配置后重新登录");
        }
    }

    /** 全院可见（无科室限制）。 */
    public static DataScope all(String actor, boolean phiReadable) {
        return new DataScope(actor, Set.of(), true, phiReadable);
    }

    /** 单科室可见。 */
    public static DataScope ofDepartment(String actor, UUID deptId, boolean phiReadable) {
        return new DataScope(actor, Set.of(deptId), false, phiReadable);
    }

    /** 角色是否属于"必须跨科室"的一类。 */
    public static boolean isAllDepartmentRole(String role) {
        return role != null && ALL_DEPARTMENT_ROLES.contains(role.trim().toUpperCase());
    }

    /**
     * 是否可以看到某个科室的数据。
     *
     * <p>科室为空一律不可见：宁可多拒一条，也不要因为数据缺字段而放开。
     */
    public boolean canSeeDepartment(UUID deptId) {
        if (allDepartments) {
            return true;
        }
        return deptId != null && deptCodes.contains(deptId);
    }

    /**
     * 患者号掩码：保留前 3 后 2，中间用 {@code *} 补足。
     *
     * <p>长度不足 6 的一律全掩码——短编号保留前后位等于直接暴露。
     */
    public String maskPatientNo(String patientNo) {
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
    public String maskPatientName(String patientName) {
        if (patientName == null || patientName.isBlank()) {
            return patientName;
        }
        String value = patientName.trim();
        if (value.length() <= 1) {
            return "*";
        }
        return value.charAt(0) + "*".repeat(value.length() - 1);
    }

    /** 按权限决定返回明文还是掩码。 */
    public String patientNoFor(String patientNo) {
        return phiReadable ? patientNo : maskPatientNo(patientNo);
    }

    /** 按权限决定返回明文还是掩码。 */
    public String patientNameFor(String patientName) {
        return phiReadable ? patientName : maskPatientName(patientName);
    }
}
