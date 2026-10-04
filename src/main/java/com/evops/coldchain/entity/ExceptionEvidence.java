package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 异常凭证（附件/照片/说明）：只追加。
 * 已落库的凭证永不更新、不覆盖、不删除；补充材料一律新增行。
 */
@TableName("t_cc_exception_evidence")
public class ExceptionEvidence extends CreateOnlyEntity {
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
