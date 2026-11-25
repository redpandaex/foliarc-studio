package studio.foliarc.biz.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> details;

    public BizException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public BizException(HttpStatus status, String code, String message, Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    public static BizException badRequest(String code, String message) {
        return new BizException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static BizException unauthorized(String code, String message) {
        return new BizException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static BizException forbidden(String code, String message) {
        return new BizException(HttpStatus.FORBIDDEN, code, message);
    }

    public static BizException vipQuotaExceeded(String message, Map<String, String> details) {
        return new BizException(HttpStatus.FORBIDDEN, "VIP_QUOTA_EXCEEDED", message, details);
    }

    public static BizException notFound(String code, String message) {
        return new BizException(HttpStatus.NOT_FOUND, code, message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(HttpStatus.CONFLICT, code, message);
    }

    public static BizException serviceUnavailable(String code, String message) {
        return new BizException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public static BizException paymentRequired(String code, String message) {
        return new BizException(HttpStatus.PAYMENT_REQUIRED, code, message);
    }

    public static BizException tooManyRequests(String code, String message) {
        return new BizException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }
}
