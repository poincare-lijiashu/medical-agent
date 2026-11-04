package com.medassist.common.security;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行级数据范围规则：全院角色 / 单科室 / fail-closed / PHI 掩码。
 *
 * <p>这些用例的作用不是"跑一遍代码"，而是把"不确定就拒绝"这条规则钉死：
 * 任何一次改动只要让缺失科室头退化成全院可见，测试立刻红。
 */
class DataScopeTest {

    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();

    // ---------------------------------------------------------------- 范围解析

    @Test
    @DisplayName("药师：跨科室可见（审方必须看到全院处方）")
    void pharmacistSeesAllDepartments() {
        DataScope scope = DataScopeResolver.resolve("pharm01", "PHARMACIST",
                "pharmacy:review", deptA.toString());

        assertThat(scope.allDepartments()).isTrue();
        assertThat(scope.canSeeDepartment(deptB)).isTrue();
        assertThat(scope.canSeeDepartment(null)).isTrue();
    }

    @Test
    @DisplayName("药师（G47）：全院科室但院区仍受限——东院区处方不对主院区药师可见")
    void pharmacistIsStillCampusScoped() {
        UUID campusMain = DataScope.DEFAULT_CAMPUS_ID;
        UUID campusEast = UUID.fromString("a0000000-0000-0000-0000-000000000002");
        DataScope scope = DataScopeResolver.resolve("pharm01", "PHARMACIST", "pharmacy:read",
                deptA.toString(), campusMain.toString());

        assertThat(scope.allDepartments()).isTrue();
        assertThat(scope.allCampuses()).isFalse();
        assertThat(scope.canSeeCampus(campusMain)).isTrue();
        assertThat(scope.canSeeCampus(campusEast)).isFalse();
        assertThat(scope.campusFilter()).containsExactly(campusMain);

        // 旧令牌没有院区头：保持不限制院区（升级前签发的令牌不会突然 403）
        DataScope legacy = DataScopeResolver.resolve("pharm01", "PHARMACIST", "pharmacy:read",
                deptA.toString(), null);
        assertThat(legacy.allCampuses()).isTrue();
    }

    @Test
    @DisplayName("医师：只看本科室，跨科室不可见")
    void doctorSeesOwnDepartmentOnly() {
        DataScope scope = DataScopeResolver.resolve("doctor01", "DOCTOR",
                "clinical:visit:read", deptA.toString());

        assertThat(scope.allDepartments()).isFalse();
        assertThat(scope.canSeeDepartment(deptA)).isTrue();
        assertThat(scope.canSeeDepartment(deptB)).isFalse();
    }

    @Test
    @DisplayName("医师 + 缺失科室头 → 403（不放开，不猜）")
    void doctorWithoutDepartmentIsRejected() {
        assertThatThrownBy(() ->
                DataScopeResolver.resolve("doctor01", "DOCTOR", "clinical:visit:read", null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("医师 + 科室头是历史编码（D013）→ 403（拿编码比 UUID 只会静默查空）")
    void doctorWithLegacyDepartmentCodeIsRejected() {
        assertThatThrownBy(() ->
                DataScopeResolver.resolve("doctor01", "DOCTOR", "clinical:visit:read", "D013"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("多科室执业：绑定的科室都可见，未绑定的不可见")
    void doctorSeesAllBoundDepartments() {
        UUID deptC = UUID.randomUUID();
        DataScope scope = DataScopeResolver.resolve("doctor01", "DOCTOR",
                "clinical:visit:read", deptA + "," + deptB);

        assertThat(scope.deptCodes()).containsExactlyInAnyOrder(deptA, deptB);
        assertThat(scope.canSeeDepartment(deptA)).isTrue();
        assertThat(scope.canSeeDepartment(deptB)).isTrue();
        assertThat(scope.canSeeDepartment(deptC)).isFalse();
        assertThat(scope.allDepartments()).isFalse();
    }

    @Test
    @DisplayName("科室列表含非法值 → 整体 403（不部分放行）")
    void departmentListWithInvalidValueIsRejected() {
        assertThatThrownBy(() ->
                DataScopeResolver.resolve("doctor01", "DOCTOR", "", deptA + ",D013"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("科室列表只有分隔符/空白 → 403")
    void departmentListWithOnlySeparatorsIsRejected() {
        assertThatThrownBy(() ->
                DataScopeResolver.resolve("doctor01", "DOCTOR", "", " , "))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("旧令牌无角色声明 → 403 并提示重新登录")
    void missingRolesIsRejected() {
        assertThatThrownBy(() ->
                DataScopeResolver.resolve("doctor01", null, null, deptA.toString()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(e.getMessage()).contains("重新登录");
                });
    }

    @Test
    @DisplayName("科室为 null 的脏数据：即使在全院范围也返回可见，单科室范围返回不可见")
    void nullDepartmentIsHandledByScope() {
        DataScope all = DataScope.all("qc01", false);
        DataScope one = DataScope.ofDepartment("doctor01", deptA, true);

        // 全院角色看的是"全院"，脏数据不构成越权
        assertThat(all.canSeeDepartment(null)).isTrue();
        // 单科室角色遇到缺科室的数据必须拒绝
        assertThat(one.canSeeDepartment(null)).isFalse();
    }

    // ---------------------------------------------------------------- PHI 掩码

    @Test
    @DisplayName("患者号掩码：保留前 3 后 2，中间补星")
    void maskPatientNoKeepsEnds() {
        DataScope masked = DataScopeResolver.resolve("qc01", "QC", "", deptA.toString());

        assertThat(masked.patientNoFor("P00000123")).isEqualTo("P00****23");
        // 长度不足 6 的短号整体掩码：保留前后位等于直接暴露
        assertThat(masked.patientNoFor("P0001")).isEqualTo("*****");
        assertThat(masked.patientNoFor(null)).isNull();
    }

    @Test
    @DisplayName("姓名掩码：只留首字")
    void maskPatientNameKeepsFirstChar() {
        DataScope masked = DataScopeResolver.resolve("qc01", "QC", "", deptA.toString());

        assertThat(masked.patientNameFor("张建国")).isEqualTo("张**");
        assertThat(masked.patientNameFor("李")).isEqualTo("*");
        assertThat(masked.patientNameFor("")).isEmpty();
    }

    @Test
    @DisplayName("有 phi:read 权限点时返回明文")
    void phiReadableReturnsPlainText() {
        DataScope full = DataScopeResolver.resolve("doctor01", "DOCTOR", "phi:read",
                deptA.toString());

        assertThat(full.phiReadable()).isTrue();
        assertThat(full.patientNoFor("P00000123")).isEqualTo("P00000123");
        assertThat(full.patientNameFor("张建国")).isEqualTo("张建国");
    }
}
