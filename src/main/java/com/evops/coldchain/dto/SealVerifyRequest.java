package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 封签核验请求：对交接单内某个封签逐条核验确认。
 * 已确认事实只进不退：重复核验同一封签会被拒绝（不能用新说明覆盖已确认事实）。
 */
public class SealVerifyRequest {
    @NotNull(message = "封签明细ID不能为空")
    private Long sealId;
    /** 核验人（不传取当前登录操作者账号） */
    private String verifiedBy;
    /** 核验说明，如“封签完好、箱内温度达标”；判损时必须写明破损情况 */
    @NotBlank(message = "核验说明不能为空（需留下确认依据）")
    private String verifyNote;
    /**
     * V6：核验结论是否发现封签破损/被破坏。
     * TRUE=核验确认封签破损：确认事实照常只进不退，同时自动登记 SEAL_BROKEN 分级异常，
     * 核验时刻即异常成立时刻（确认启钟）；FALSE/空=封签完好。
     */
    private Boolean broken;

    public Long getSealId() { return sealId; }
    public void setSealId(Long sealId) { this.sealId = sealId; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public String getVerifyNote() { return verifyNote; }
    public void setVerifyNote(String verifyNote) { this.verifyNote = verifyNote; }
    public Boolean getBroken() { return broken; }
    public void setBroken(Boolean broken) { this.broken = broken; }
}
