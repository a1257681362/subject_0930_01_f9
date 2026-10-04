package com.evops.config;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authz.PermissionsAuthorizationFilter;
import org.apache.shiro.web.util.WebUtils;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class ShiroConfig {

    @Bean
    public Realm localRealm() {
        return new ColdChainRealm();
    }

    /**
     * 覆盖 Shiro 默认 perms 过滤器：未认证（理论上前面的 authcBasic 已拦截）走登录质询，
     * 已认证但缺少权限点返回 403（默认实现返回 401，语义应为禁止访问）。
     * Bean 名必须为 perms，Shiro 自动配置会按名合并进过滤器表替换内置 perms。
     */
    @Bean(name = "perms")
    public PermissionsAuthorizationFilter permsFilter() {
        return new PermissionsAuthorizationFilter() {
            @Override
            protected boolean onAccessDenied(ServletRequest request, ServletResponse response)
                    throws IOException {
                Subject subject = getSubject(request, response);
                if (subject.getPrincipal() == null) {
                    saveRequestAndRedirectToLogin(request, response);
                } else {
                    HttpServletResponse http = WebUtils.toHttp(response);
                    http.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Forbidden：当前账号无所需 Shiro 权限点");
                }
                return false;
            }
        };
    }

    /** 阻止 Spring Boot 把 Shiro 内部 perms 过滤器再注册到容器主过滤链（它只在 Shiro 链内使用） */
    @Bean
    public FilterRegistrationBean<PermissionsAuthorizationFilter> permsFilterRegistration(
            PermissionsAuthorizationFilter permsFilter) {
        FilterRegistrationBean<PermissionsAuthorizationFilter> registration = new FilterRegistrationBean<>(permsFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/error", "anon");

        // —— V5 温控预警：查看 / 导出 / 闭环 / 手动扫描 分别按 Shiro 权限点校验 ——
        // 无权限已登录用户返回 403；未携带 Basic 凭证返回 401（由 authcBasic 先认证）
        chain.addPathDefinition("/api/cold-chain/alerts/export",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_ALERT_EXPORT + "]");
        chain.addPathDefinition("/api/cold-chain/alerts/scan",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_ALERT_SCAN + "]");
        chain.addPathDefinition("/api/cold-chain/alerts/*/resolve",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_ALERT_RESOLVE + "]");
        chain.addPathDefinition("/api/cold-chain/alerts/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_ALERT_VIEW + "]");

        // —— V5 药品类别 + 温控预警模板维护（写操作）——
        chain.addPathDefinition("/api/cold-chain/categories",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_TEMPLATE_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/categories/*/template",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_TEMPLATE_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/categories/*/change",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_TEMPLATE_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/categories/*/revoke",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_TEMPLATE_MANAGE + "]");
        // 类别树/详情的只读查看走查看权限（具体维护路径在前、宽泛只读路径在后）
        chain.addPathDefinition("/api/cold-chain/categories/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_ALERT_VIEW + "]");
        chain.addPathDefinition("/api/cold-chain/drugs/*/category",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_TEMPLATE_MANAGE + "]");
        // 预警管理页面同样需要查看权限（页面内各操作再经上面 API 权限点二次校验）
        chain.addPathDefinition("/ui/cold-chain/alerts",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_ALERT_VIEW + "]");

        // —— V6 运输异常分级与处置时钟（具体路径在前，宽泛只读在后）——
        // 分级规则查看/调整同一路径，统一要求规则管理权限（与 V5 /categories 同做法）
        chain.addPathDefinition("/api/cold-chain/exceptions/grade-rules",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_RULE_MANAGE + "]");
        // 处置时钟手动扫描（运维入口）：规则管理权限
        chain.addPathDefinition("/api/cold-chain/exceptions/clock/scan",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_RULE_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/exceptions/*/confirm",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_CONFIRM + "]");
        chain.addPathDefinition("/api/cold-chain/exceptions/*/conclude",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_CONCLUDE + "]");
        chain.addPathDefinition("/api/cold-chain/exceptions/*/review",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_REVIEW + "]");
        chain.addPathDefinition("/api/cold-chain/exceptions/*/notify-retry",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_NOTIFY_RETRY + "]");
        // 异常详情/通知与复核记录只读
        chain.addPathDefinition("/api/cold-chain/exceptions/**", "authcBasic");

        // 收货拒签登记 / 批次结算
        chain.addPathDefinition("/api/cold-chain/batches/reject",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_EXCEPTION_CONFIRM + "]");
        chain.addPathDefinition("/api/cold-chain/batches/settle",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_BATCH_SETTLE + "]");

        // —— V7 药品运输抽检（具体写路径在前，宽泛只读在后）——
        // 导出：独立导出权限（承运人联系方式脱敏）
        chain.addPathDefinition("/api/cold-chain/inspections/export",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_INSPECTION_EXPORT + "]");
        // 样本封存/双人转移等办理动作
        chain.addPathDefinition("/api/cold-chain/inspections/samples/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_INSPECTION_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/inspections/*/conclude",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_INSPECTION_MANAGE + "]");
        // 列表/立项同路径（GET 列表 / POST 立项）：先过查看权限，立项在服务内二次校验 manage
        chain.addPathDefinition("/api/cold-chain/inspections",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_INSPECTION_VIEW + "]");
        // 抽检详情等只读（必须放在精确路径之后）
        chain.addPathDefinition("/api/cold-chain/inspections/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_INSPECTION_VIEW + "]");

        // —— V7 冷链药品运输审批（两步：申请 -> 同意/驳回）——
        chain.addPathDefinition("/api/cold-chain/approvals/pending",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_APPROVAL_REVIEW + "]");
        chain.addPathDefinition("/api/cold-chain/approvals/*/approve",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_APPROVAL_REVIEW + "]");
        chain.addPathDefinition("/api/cold-chain/approvals/*/reject",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_APPROVAL_REVIEW + "]");
        // 申请（调度；必须放在宽泛只读路径之前，先匹配先生效）
        chain.addPathDefinition("/api/cold-chain/approvals",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_APPROVAL_APPLY + "]");
        // 审批详情/批次审批链只读：登录即可（区域隔离在服务层兜底）
        chain.addPathDefinition("/api/cold-chain/approvals/**", "authcBasic");

        // —— V8 冷链承运商报价比选与锁价（写路径在前、宽泛只读在后）——
        // 承运商建档
        chain.addPathDefinition("/api/cold-chain/carriers/create",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_CARRIER_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/carriers",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_VIEW + "]");
        // 报价批次：发起/截止/过期扫描（调度），承运商提交报价
        chain.addPathDefinition("/api/cold-chain/quotes/batches/open",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_BATCH_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/quotes/batches/*/close",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_BATCH_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/quotes/expire-scan",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_BATCH_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/quotes/submit",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_SUBMIT + "]");
        // 锁价 / 作废 / 人工改价申请 / 改价审批
        chain.addPathDefinition("/api/cold-chain/locked-prices/lock",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_PRICE_LOCK + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices/*/void",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_PRICE_LOCK + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices/adjustments/pending",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_PRICE_ADJUST_REVIEW + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices/adjustments/*/approve",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_PRICE_ADJUST_REVIEW + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices/adjustments/*/reject",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_PRICE_ADJUST_REVIEW + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices/adjustments",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_PRICE_ADJUST_APPLY + "]");
        // 报价/锁价只读（比选/详情/反查/列表）：统一查看权限
        chain.addPathDefinition("/api/cold-chain/quotes/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_VIEW + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_VIEW + "]");
        chain.addPathDefinition("/api/cold-chain/locked-prices/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_QUOTE_VIEW + "]");

        // —— V9 车载设备温度数据接入（具体路径在前，宽泛只读在后）——
        // 设备证书登记/管理
        chain.addPathDefinition("/api/cold-chain/device/certs",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_DEVICE_CERT_MANAGE + "]");
        chain.addPathDefinition("/api/cold-chain/device/certs/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_DEVICE_CERT_MANAGE + "]");
        // 设备温度批量上报（设备网关账号）
        chain.addPathDefinition("/api/cold-chain/device/temperatures",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_DEVICE_INGEST + "]");
        // 重放隔离数据
        chain.addPathDefinition("/api/cold-chain/device/quarantine/*/replay",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_DEVICE_REPLAY + "]");
        // 隔离队列/数据批次/重放审计只读
        chain.addPathDefinition("/api/cold-chain/device/quarantine/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_DEVICE_QUARANTINE_VIEW + "]");
        chain.addPathDefinition("/api/cold-chain/device/ingest-batches/**",
                "authcBasic, perms[" + com.evops.coldchain.CcConstants.PERM_DEVICE_QUARANTINE_VIEW + "]");

        // 冷链台账接口与管理页面使用 HTTP Basic，便于 curl/联调；浏览器会弹出登录框
        chain.addPathDefinition("/api/**", "authcBasic");
        chain.addPathDefinition("/ui/**", "authcBasic");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
