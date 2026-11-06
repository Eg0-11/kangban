package com.kangban.agent.internal;

import com.kangban.common.BusinessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InternalAgentController.class)
public class InternalAgentExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalAgentError> handle(Exception exception) {
        InternalAgentException error;
        if (exception instanceof InternalAgentException internal) {
            error = internal;
        } else if (exception instanceof HttpMessageNotReadableException || exception instanceof HttpMediaTypeNotSupportedException) {
            error = InternalAgentException.invalidRequest();
        } else if (exception instanceof BusinessException business) {
            error = new InternalAgentException(business.getHttpStatus(), "BUSINESS_REQUEST_REJECTED",
                    "当前业务请求无法处理，请检查患者权限和输入。", false);
        } else if (exception instanceof DataAccessException) {
            error = new InternalAgentException(503, "DEPENDENCY_UNAVAILABLE", "内部业务服务暂时不可用。", true);
        } else if (exception instanceof HttpRequestMethodNotSupportedException) {
            error = new InternalAgentException(405, "METHOD_NOT_ALLOWED", "内部 Agent 请求方法无效。", false);
        } else if (exception instanceof NoResourceFoundException) {
            error = new InternalAgentException(404, "NOT_FOUND", "内部 Agent 接口不存在。", false);
        } else {
            error = new InternalAgentException(500, "INTERNAL_ERROR", "内部 Agent 服务暂时不可用。", true);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String runId = authentication != null && authentication.getPrincipal() instanceof InternalAgentCall call
                ? call.context().runId() : null;
        return ResponseEntity.status(error.status()).body(InternalAgentError.from(error, runId));
    }
}
