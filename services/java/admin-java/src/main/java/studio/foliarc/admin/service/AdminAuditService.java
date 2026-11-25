package studio.foliarc.admin.service;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import studio.foliarc.biz.audit.entity.AdminAuditLogEntity;
import studio.foliarc.biz.audit.mapper.AdminAuditLogMapper;
import studio.foliarc.biz.auth.AuthenticatedUser;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminAuditService {

    private final AdminAuditLogMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest request;

    public AdminAuditService(AdminAuditLogMapper auditMapper, ObjectMapper objectMapper, HttpServletRequest request) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.request = request;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthenticatedUser operator, String action, String targetType, Object targetId,
                       Object before, Object after) {
        AdminAuditLogEntity log = new AdminAuditLogEntity();
        log.setId(UUID.randomUUID());
        log.setOperatorId(operator.userId());
        log.setOperatorName(operator.username());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId.toString());
        log.setBeforeJson(before == null ? null : objectMapper.writeValueAsString(before));
        log.setAfterJson(after == null ? null : objectMapper.writeValueAsString(after));
        log.setClientIp(boundedContext(request.getRemoteAddr()));
        String traceId = MDC.get("traceId");
        log.setTraceId(boundedContext(traceId == null ? request.getHeader("X-Trace-Id") : traceId));
        auditMapper.insert(log);
    }

    private static String boundedContext(String value) {
        return value == null ? "" : value.substring(0, Math.min(64, value.length()));
    }
}
