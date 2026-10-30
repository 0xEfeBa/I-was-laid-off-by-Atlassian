package dev.triedge.broker.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CallerResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Caller.class);
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) {
        String id = request.getHeader(Caller.USER_ID_HEADER);
        String role = request.getHeader(Caller.USER_ROLE_HEADER);
        if (id == null || role == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return new Caller(UUID.fromString(id), role);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }
}
