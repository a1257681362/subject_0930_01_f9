package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 统一操作留痕：关键动作调用一次即追加一行（操作者账号 + 时间由框架/默认值落库）。
 * 日志本身只插入，不提供修改与删除。
 */
@Service
public class OpLogService {

    @Resource
    private OpLogMapper opLogMapper;

    public void log(String entityType, Long entityId, Long batchId, String action, String summary) {
        OpLog opLog = new OpLog();
        opLog.setEntityType(entityType);
        opLog.setEntityId(entityId);
        opLog.setBatchId(batchId);
        opLog.setAction(action);
        opLog.setSummary(summary);
        opLog.setOperator(UserContext.currentName());
        opLogMapper.insert(opLog);
    }

    /** 按批次串联全链路操作留痕（时间升序） */
    public List<OpLog> listByBatch(Long batchId) {
        return opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }
}
