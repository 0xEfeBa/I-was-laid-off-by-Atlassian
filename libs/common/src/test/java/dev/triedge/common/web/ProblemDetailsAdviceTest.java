package dev.triedge.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsAdviceTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final Tracer tracer = mock(Tracer.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ProblemDetailsAdvice(tracer))
            .build();

    @Test
    void unexpectedErrorBecomesProblemWithoutInternalDetails() throws Exception {
        givenActiveTrace(TRACE);

        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.trace_id").value(TRACE))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void knownErrorKeepsItsStatusAndDetail() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Repository not found"));
    }

    @Test
    void traceIdIsOmittedWithoutActiveSpan() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(jsonPath("$.trace_id").doesNotExist());
    }

    private void givenActiveTrace(String traceId) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
    }

    @RestController
    static class FailingController {

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }

        @GetMapping("/missing")
        String missing() {
            throw new ErrorResponseException(
                    HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Repository not found"),
                    null);
        }
    }
}
