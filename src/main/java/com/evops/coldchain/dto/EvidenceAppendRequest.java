package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 异常凭证追加请求。
 * 凭证永远新增一行：不覆盖、不更新、不删除已有凭证。
 */
public class EvidenceAppendRequest {
    @NotNull(message = "异常事件ID不能为空")
    private Long exceptionId;
    private String fileName;
    private String fileUrl;
    private String note;

    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
