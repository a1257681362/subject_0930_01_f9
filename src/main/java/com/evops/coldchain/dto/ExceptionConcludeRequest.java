package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 异常处置结论提交请求（V6）。
 * 结论必须引用至少一条本异常名下的证据（t_cc_exception_evidence 行ID），
 * 提交后进入 REVIEWING，由分级规则指定的复核岗位复核通过才能关闭。
 */
public class ExceptionConcludeRequest {
    @NotBlank(message = "处置结论不能为空：关闭异常必须给出结论说明")
    private String conclusion;
    /** 引用的证据ID列表（必须属于本异常，至少一条） */
    @NotEmpty(message = "处置结论必须引用证据：至少引用一条凭证/照片/说明")
    private List<Long> evidenceIds;

    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public List<Long> getEvidenceIds() { return evidenceIds; }
    public void setEvidenceIds(List<Long> evidenceIds) { this.evidenceIds = evidenceIds; }
}
