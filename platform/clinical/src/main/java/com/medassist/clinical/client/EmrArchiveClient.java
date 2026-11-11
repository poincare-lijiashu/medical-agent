package com.medassist.clinical.client;

import java.util.Optional;
import java.util.UUID;

/**
 * 电子病历归档核查（只读出站，内网直连）。
 *
 * <p>为什么单独抽接口：出院体检是只读聚合，EMR 不可达时只能"降级为未核查"，
 * 不能让整个体检 500；把失败语义收在客户端里，体检服务只关心"有没有拿到事实"。
 */
public interface EmrArchiveClient {

    /**
     * 本次就诊的病历归档情况。
     *
     * @return empty 表示未接入或暂不可达——调用方必须显示"未核查"，
     *         不能当成"已归档"（宁可少说，不能说错）
     */
    Optional<ArchiveSummary> archiveSummary(UUID visitId);

    /**
     * 归档摘要：只含计数与首页状态，不含病历标题/作者/正文。
     */
    record ArchiveSummary(int total, int archived, int draft, boolean homepageFound,
                          String homepageStatus, boolean homepageArchived) {

        /** 未归档份数（防御上游算术不一致，不返回负数）。 */
        public int unarchived() {
            return Math.max(0, total - archived);
        }
    }
}
