package com.evops.common;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 审计字段自动填充：所有 INSERT/UPDATE 自动留下操作者与时间。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = UserContext.currentId();

        if (metaObject.hasSetter("createBy") && isNull(metaObject, "createBy")) {
            this.strictInsertFill(metaObject, "createBy", Long.class, userId);
        }
        if (metaObject.hasSetter("createTime") && isNull(metaObject, "createTime")) {
            this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        }
        if (metaObject.hasSetter("updateBy")) {
            this.strictInsertFill(metaObject, "updateBy", Long.class, userId);
        }
        if (metaObject.hasSetter("updateTime")) {
            this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateBy", Long.class, UserContext.currentId());
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    private boolean isNull(MetaObject metaObject, String field) {
        Object value = metaObject.getValue(field);
        if (value instanceof CharSequence) {
            return ((CharSequence) value).length() == 0;
        }
        return value == null;
    }
}
