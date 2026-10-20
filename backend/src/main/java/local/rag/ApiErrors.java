package local.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiTypes.Error> api(ApiException e) { return error(e.status,e.code,e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiTypes.Error> tooLarge() { return error(413,"FILE_TOO_LARGE","单文件不能超过20 MiB"); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,MissingServletRequestPartException.class,MultipartException.class})
    ResponseEntity<ApiTypes.Error> invalid() { return error(400,"INVALID_REQUEST","请求格式或参数无效，请检查后重试"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiTypes.Error> notFound() { return error(404,"NOT_FOUND","接口不存在"); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiTypes.Error> media() { return error(400,"INVALID_CONTENT_TYPE","请求Content-Type不支持"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiTypes.Error> method() { return error(405,"METHOD_NOT_ALLOWED","请求方法不支持"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiTypes.Error> unexpected() { return error(500,"INTERNAL_ERROR","服务暂时不可用，请稍后重试"); }
    private ResponseEntity<ApiTypes.Error> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(new ApiTypes.Error(code,message));
    }
}
