package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.GradeRuleChangeRequest;
import com.evops.coldchain.entity.ExceptionGradeRule;
import com.evops.coldchain.mapper.ExceptionGradeRuleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * 异常分级规则（V6）。
 *
 * 四类异常（温度超限/封签破损/延误/收货拒签）各一行规则：等级、响应时限、处置时限、
 * 处置岗位、升级岗位、关闭前复核岗位。规则允许调整（原地更新该类型唯一行并留痕），
 * 但调整只影响“此后确认”的异常——异常确认瞬间由
 * {@link com.evops.coldchain.entity.ExceptionEvent} 复制快照，在途处置时钟不随规则变动，
 * 与档案快照、路线分段快照、预警模板快照同一口径。
 */
@Service
public class ExceptionGradeService {

    /** 纳入分级机制的四类异常（其它旧类型如 DAMAGE/OTHER 无规则，不能进入分级处置） */
    public static final List<String> GRADED_TYPES = Arrays.asList(
            CcConstants.EXC_TEMP,
            CcConstants.EXC_SEAL_BROKEN,
            CcConstants.EXC_DELAY,
            CcConstants.EXC_REJECTION);

    @Resource
    private ExceptionGradeRuleMapper ruleMapper;
    @Resource
    private OpLogService opLogService;

    public List<ExceptionGradeRule> listRules() {
        return ruleMapper.selectList(new QueryWrapper<ExceptionGradeRule>().orderByAsc("id"));
    }

    public ExceptionGradeRule requireRule(String exceptionType) {
        ExceptionGradeRule rule = ruleMapper.selectOne(new QueryWrapper<ExceptionGradeRule>()
                .eq("exception_type", exceptionType).last("LIMIT 1"));
        if (rule == null) {
            throw new BusinessException("异常类型[" + exceptionType + "]未配置分级规则，不能启动分级处置时钟");
        }
        if (!Boolean.TRUE.equals(rule.getEnabled())) {
            throw new BusinessException("异常类型[" + exceptionType + "]的分级规则已停用，不能确认异常");
        }
        return rule;
    }

    /**
     * 调整分级规则（按类型唯一行原地更新）。只影响此后确认的异常；
     * 已确认异常持有的等级/时限/岗位快照不变。
     */
    @Transactional
    public ExceptionGradeRule changeRule(GradeRuleChangeRequest req) {
        String type = req.getExceptionType() == null ? null : req.getExceptionType().trim().toUpperCase();
        if (!GRADED_TYPES.contains(type)) {
            throw new BusinessException("异常类型不在分级机制内（仅支持温度超限/封签破损/延误/收货拒签）: " + type);
        }
        if (req.getResponseMinutes() == null || req.getDeadlineMinutes() == null
                || req.getResponseMinutes() < 0 || req.getDeadlineMinutes() < 0) {
            throw new BusinessException("响应时限与处置时限必须为不小于0的分钟数");
        }
        if (req.getDeadlineMinutes() < req.getResponseMinutes()) {
            throw new BusinessException("处置时限不得短于响应时限");
        }
        validateGrade(req.getGrade());
        requireRole(req.getOwnerRole(), "处置岗位");
        requireRole(req.getEscalateRole(), "升级岗位");
        requireRole(req.getReviewerRole(), "复核岗位");

        ExceptionGradeRule rule = requireRuleEvenDisabled(type);
        String before = String.format("%s/%s 响应%dm 处置%dm 处置岗=%s 升级岗=%s 复核岗=%s",
                rule.getGrade(), rule.getGradeName(), rule.getResponseMinutes(), rule.getDeadlineMinutes(),
                rule.getOwnerRole(), rule.getEscalateRole(), rule.getReviewerRole());

        rule.setGrade(req.getGrade().trim().toUpperCase());
        rule.setGradeName(req.getGradeName().trim());
        rule.setResponseMinutes(req.getResponseMinutes());
        rule.setDeadlineMinutes(req.getDeadlineMinutes());
        rule.setOwnerRole(req.getOwnerRole().trim().toUpperCase());
        rule.setEscalateRole(req.getEscalateRole().trim().toUpperCase());
        rule.setReviewerRole(req.getReviewerRole().trim().toUpperCase());
        rule.setNotifyEndpoint(req.getNotifyEndpoint() == null || req.getNotifyEndpoint().trim().isEmpty()
                ? null : req.getNotifyEndpoint().trim());
        if (req.getEnabled() != null) {
            rule.setEnabled(req.getEnabled());
        }
        rule.setRemark(req.getRemark());
        ruleMapper.updateById(rule);

        opLogService.log(CcConstants.ENT_EXCEPTION, rule.getId(), null, CcConstants.ACT_EXC_RULE_CHANGE,
                String.format("异常分级规则[%s/%s]调整：%s -> %s/%s 响应%dm 处置%dm 处置岗=%s 升级岗=%s 复核岗=%s；操作者=%s（只影响此后确认的异常）",
                        type, rule.getExceptionTypeName(), before,
                        rule.getGrade(), rule.getGradeName(), rule.getResponseMinutes(), rule.getDeadlineMinutes(),
                        rule.getOwnerRole(), rule.getEscalateRole(), rule.getReviewerRole(),
                        UserContext.currentName()));
        return rule;
    }

    private ExceptionGradeRule requireRuleEvenDisabled(String type) {
        ExceptionGradeRule rule = ruleMapper.selectOne(new QueryWrapper<ExceptionGradeRule>()
                .eq("exception_type", type).last("LIMIT 1"));
        if (rule == null) {
            throw new BusinessException("异常类型[" + type + "]的分级规则缺失（默认规则应由 schema 初始化）");
        }
        return rule;
    }

    private void validateGrade(String grade) {
        if (grade == null) {
            throw new BusinessException("等级不能为空");
        }
        String g = grade.trim().toUpperCase();
        if (!CcConstants.GRADE_L1.equals(g) && !CcConstants.GRADE_L2.equals(g) && !CcConstants.GRADE_L3.equals(g)) {
            throw new BusinessException("等级仅支持 L1 重大 / L2 较大 / L3 一般: " + grade);
        }
    }

    private void requireRole(String role, String label) {
        if (role == null || role.trim().isEmpty()) {
            throw new BusinessException(label + "不能为空");
        }
    }
}
