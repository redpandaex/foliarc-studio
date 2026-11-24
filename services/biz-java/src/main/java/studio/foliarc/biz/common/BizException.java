package studio.foliarc.biz.common;

import org.springframework.http.HttpStatus;

public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BizException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
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

    public static BizException notFound(String code, String message) {
        return new BizException(HttpStatus.NOT_FOUND, code, message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(HttpStatus.CONFLICT, code, message);
    }

    public static BizException serviceUnavailable(String code, String message) {
        return new BizException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
