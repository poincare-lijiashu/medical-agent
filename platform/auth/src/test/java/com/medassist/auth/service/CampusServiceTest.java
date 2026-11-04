package com.medassist.auth.service;

import com.medassist.auth.domain.Campus;
import com.medassist.auth.repo.CampusRepository;
import com.medassist.auth.web.dto.CreateCampusReq;
import com.medassist.auth.web.dto.UpdateCampusReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 院区维护业务规则（G46）：编码归一、唯一性、最后一个启用院区不可停用、CAS 冲突。 */
class CampusServiceTest {

    private CampusRepository repo;
    private CampusService service;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(CampusRepository.class);
        service = new CampusService(repo, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("新建：编码统一大写，落库后返回最新记录")
    void createNormalizesCode() {
        when(repo.existsByCode("WEST")).thenReturn(false);
        when(repo.findById(any())).thenReturn(Optional.of(
                new Campus(id, "WEST", "西院区", Campus.ACTIVE, 30, 0L)));

        Campus created = service.create(new CreateCampusReq("west", " 西院区 ", 30), "admin01");

        assertThat(created.code()).isEqualTo("WEST");
        verify(repo).insert(any(Campus.class));
        verify(repo).logChange(eq("admin01"), eq("CAMPUS_CREATE"), anyString(), anyString());
    }

    @Test
    @DisplayName("新建：编码重复直接 409，不写库")
    void createRejectsDuplicateCode() {
        when(repo.existsByCode("WEST")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCampusReq("west", "西院区", 30), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insert(any());
    }

    @Test
    @DisplayName("停用：最后一个启用院区不可停用")
    void updateRejectsDisablingLastActiveCampus() {
        when(repo.findById(id)).thenReturn(Optional.of(
                new Campus(id, "MAIN", "主院区", Campus.ACTIVE, 10, 0L)));
        when(repo.countActive()).thenReturn(1L);

        assertThatThrownBy(() -> service.update(id,
                new UpdateCampusReq("主院区", Campus.DISABLED, 10, 0L), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).update(any(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("修改：版本冲突转 409，提示刷新后重试")
    void updateTranslatesCasConflict() {
        when(repo.findById(id)).thenReturn(Optional.of(
                new Campus(id, "MAIN", "主院区", Campus.ACTIVE, 10, 3L)));
        when(repo.update(eq(id), anyString(), anyString(), anyInt(), eq(3L))).thenReturn(false);

        assertThatThrownBy(() -> service.update(id,
                new UpdateCampusReq("主院区（新）", Campus.ACTIVE, 10, 3L), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
