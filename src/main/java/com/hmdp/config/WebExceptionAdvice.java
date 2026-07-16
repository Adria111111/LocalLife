package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局统一异常处理配置类
 * 作用：捕获所有Controller抛出的异常，统一封装Result返回前端，消除接口重复try-catch
 * @RestControllerAdvice 全局生效，对所有Rest控制器拦截异常
 */

@Slf4j
@RestControllerAdvice // 是整个后端项目统一处理 Controller 异常的地方
public class WebExceptionAdvice {

    /**
     * 处理数据库唯一索引冲突异常
     * @param e DuplicateKeyException
     * @return Result
     * 插入订单冲突，抛出 DuplicateKeyException；
     * 无捕获，异常直接跑出 @Transactional 方法；
     * Spring 检测到异常，立刻回滚已修改的库存；
     * 异常继续向上传递，进入全局异常处理器；
     * 全局统一封装 Result.fail() 友好提示返回前端。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result handleDuplicateKeyException(DuplicateKeyException e) {
        log.warn("数据库唯一约束阻止了重复写入: {}", e.getMessage());
        if (e.getMessage() != null && e.getMessage().contains("uk_user_voucher")) {
            return Result.fail("不能重复购买同一张优惠券");
        }
        return Result.fail("数据已存在，请勿重复提交");
    }

    /**
     * 处理运行时异常
     * @param e RuntimeException
     * @return Result
     */
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
