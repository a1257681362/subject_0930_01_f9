package com.evops.coldchain.support;

import com.evops.common.UserPrincipal;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测试辅助：为服务直调用例绑定一个带指定角色的 Shiro 登录主体。
 *
 * 服务直调默认操作者回退为 system 且无任何角色，无法验证“关闭前必须由 QA 岗位复核”。
 * 借助容器中的 Shiro {@link SecurityManager}，用 ColdChainRealm 的真实 realm 名构造
 * 已认证主体（角色/权限仍由 Realm 按用户名解析，username 必须是内置账号：
 * qa/admin/dispatcher/carrier/receiver），在回调执行期间绑定线程上下文，结束后清理。
 */
@Component
public class TestSubjects {

    @Autowired
    private SecurityManager securityManager;

    public void runAs(long id, String username, Runnable action) {
        String realmName = resolveRealmName();
        UserPrincipal principal = new UserPrincipal(id, username);
        SimplePrincipalCollection principals = new SimplePrincipalCollection(principal, realmName);
        Subject subject = new Subject.Builder(securityManager)
                .principals(principals)
                .authenticated(true)
                .buildSubject();
        SubjectThreadState state = new SubjectThreadState(subject);
        state.bind();
        try {
            action.run();
        } finally {
            state.restore();
        }
    }

    private String resolveRealmName() {
        return ((org.apache.shiro.mgt.RealmSecurityManager) securityManager)
                .getRealms().iterator().next().getName();
    }
}
