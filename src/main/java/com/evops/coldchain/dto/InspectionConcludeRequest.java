package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 抽检结论请求：结论回写运输批次的独立抽检列，
 * 不修改批次状态/温度/异常/交接等任何原始运输记录；结论只追加一次。
 */
public class InspectionConcludeRequest {
    /** 抽检任务ID（由路径 /inspections/{id}/conclude 回填，无需请求体携带） */
    private Long taskId;
    /** PASS 合格 / FAIL 不合格 */
    @NotBlank(message = "抽检结论不能为空")
    private String result;
    @NotBlank(message = "抽检结论说明不能为空")
    private String conclusion;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
}
