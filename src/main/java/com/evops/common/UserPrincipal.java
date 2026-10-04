package com.evops.common;

import java.io.Serializable;

/**
 * Shiro 登录主体：携带操作者 ID 与账号名。
 * 账号名进入 t_cc_op_log.operator，ID 进入各业务表 create_by/update_by。
 */
public class UserPrincipal implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String username;

    public UserPrincipal(Long id, String username) {
        this.id = id;
        this.username = username;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }

    @Override
    public String toString() {
        return username;
    }
}
