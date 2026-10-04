package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 异常处理闭环请求：向已存在的异常事件“追加”处理说明，不改原描述/凭证。异常ID来自路径变量 */
public class ExceptionResolveRequest {
    @NotBlank(message = "处理说明不能为空")
    private String resolveNote;

    public String getResolveNote() { return resolveNote; }
    public void setResolveNote(String resolveNote) { this.resolveNote = resolveNote; }
}
