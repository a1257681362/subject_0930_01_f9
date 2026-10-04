package com.evops.common;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * 当前操作者解析。优先取 Shiro 登录主体；
 * 无线程上下文时（如单元测试直调服务）回退为系统操作者，避免审计字段为空。
 */
public final class UserContext {

    public static final Long SYSTEM_ID = 0L;
    public static final String SYSTEM_NAME = "system";

    private UserContext() {
    }

    public static UserPrincipal current() {
        try {
            Subject subject = SecurityUtils.getSubject();
            Object principal = subject.getPrincipal();
            if (principal instanceof UserPrincipal) {
                return (UserPrincipal) principal;
            }
            if (principal instanceof String) {
                return new UserPrincipal(Math.abs((long) principal.hashCode()), (String) principal);
            }
        } catch (Exception ignored) {
            // 无 Shiro 环境（测试直连等），走系统回退
        }
        return new UserPrincipal(SYSTEM_ID, SYSTEM_NAME);
    }

    public static Long currentId() {
        return current().getId();
    }

    public static String currentName() {
        return current().getUsername();
    }
}
