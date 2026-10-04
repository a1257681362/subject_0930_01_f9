package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 探头校准凭证（校准历史只追加）。
 * 新一轮校准 = 新传一行 VALID 凭证，旧 VALID 凭证由服务层置为 SUPERSEDED 口径由探头
 * currentCertId 表达；凭证本身 VALID / VOID，作废只追加 void* 字段，不删除文件、不改原内容。
 * 文件上传时计算 SHA-256 摘要；每次下载记 lastDownloadTime 并写操作流水。
 */
@TableName("t_cc_probe_cert")
public class ProbeCert extends BaseEntity {
    private Long probeId;
    private String certNo;
    private String calibrateOrg;
    private LocalDateTime calibrateDate;
    private LocalDateTime validUntil;
    private BigDecimal rangeMin;
    private BigDecimal rangeMax;
    private String fileName;
    /** 证书文件内容（演示环境入库；生产应转对象存储，库存 URL） */
    private String fileContent;
    private Long fileSize;
    /** 证书内容 SHA-256 摘要（64 位十六进制），上传时生成 */
    private String sha256;
    /** VALID / VOID */
    private String status;
    private String voidReason;
    private LocalDateTime voidTime;
    /** 最近下载记账时间（下载留痕；不改变证书原始内容） */
    private LocalDateTime lastDownloadTime;

    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getCertNo() { return certNo; }
    public void setCertNo(String certNo) { this.certNo = certNo; }
    public String getCalibrateOrg() { return calibrateOrg; }
    public void setCalibrateOrg(String calibrateOrg) { this.calibrateOrg = calibrateOrg; }
    public LocalDateTime getCalibrateDate() { return calibrateDate; }
    public void setCalibrateDate(LocalDateTime calibrateDate) { this.calibrateDate = calibrateDate; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public BigDecimal getRangeMin() { return rangeMin; }
    public void setRangeMin(BigDecimal rangeMin) { this.rangeMin = rangeMin; }
    public BigDecimal getRangeMax() { return rangeMax; }
    public void setRangeMax(BigDecimal rangeMax) { this.rangeMax = rangeMax; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileContent() { return fileContent; }
    public void setFileContent(String fileContent) { this.fileContent = fileContent; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(LocalDateTime voidTime) { this.voidTime = voidTime; }
    public LocalDateTime getLastDownloadTime() { return lastDownloadTime; }
    public void setLastDownloadTime(LocalDateTime lastDownloadTime) { this.lastDownloadTime = lastDownloadTime; }
}
