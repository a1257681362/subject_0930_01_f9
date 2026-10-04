package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 校准凭证上传请求。新一轮校准即上传新凭证（旧凭证保留不删）。
 * 量程不填则沿用探头建档量程；上传后服务端对文件内容生成 SHA-256 摘要。
 */
public class ProbeCertUploadRequest {
    @NotNull(message = "探头ID不能为空")
    private Long probeId;
    @NotBlank(message = "校准证书编号不能为空")
    private String certNo;
    @NotBlank(message = "校准机构不能为空")
    private String calibrateOrg;
    @NotNull(message = "校准日期不能为空")
    private LocalDateTime calibrateDate;
    @NotNull(message = "校准有效期不能为空")
    private LocalDateTime validUntil;
    private BigDecimal rangeMin;
    private BigDecimal rangeMax;
    @NotBlank(message = "证书文件名不能为空")
    private String fileName;
    /** 证书内容（演示环境入库；生产可传对象存储 key，由服务端取内容算摘要） */
    private String fileContent;

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
}
