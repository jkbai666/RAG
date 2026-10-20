package local.rag;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    public ApiException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    public static ApiException upstream(Throwable error, String service) {
        // Deliberately do not copy upstream exception messages, bodies or URLs into public errors/logs.
        for (Throwable c = error; c != null; c = c.getCause()) {
            if (c instanceof java.net.http.HttpTimeoutException || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.util.concurrent.TimeoutException
                    || c.getClass().getSimpleName().contains("Timeout"))
                return new ApiException(504, "UPSTREAM_TIMEOUT", service + "响应超时，请稍后重试");
        }
        return new ApiException(502, "UPSTREAM_FAILURE", service + "调用失败，请检查服务状态后重试");
    }
}
