package studio.foliarc.biz.common;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

import jakarta.servlet.http.HttpServletRequest;

public final class TraceContext {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String REQUEST_ATTRIBUTE = "foliarc.traceId";

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    private TraceContext() {}

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    public static String extractOrGenerate(HttpServletRequest request) {
        String traceId = request.getHeader(HEADER_NAME);
        if (traceId != null && SAFE_TRACE_ID.matcher(traceId).matches()) {
            return traceId;
        }

        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            var match = TRACEPARENT.matcher(traceparent);
            if (match.matches() && !"0".repeat(32).equals(match.group(1)) && !"0".repeat(16).equals(match.group(2))) {
                return match.group(1);
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
