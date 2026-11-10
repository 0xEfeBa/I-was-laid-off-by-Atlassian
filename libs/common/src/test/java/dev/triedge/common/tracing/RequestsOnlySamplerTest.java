package dev.triedge.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestsOnlySamplerTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private final Sampler sampler = new RequestsOnlySampler(Sampler.parentBased(Sampler.alwaysOn()));

    @Test
    void incomingRequestsStartTraces() {
        assertThat(decision(Context.root(), SpanKind.SERVER)).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    @Test
    void backgroundWorkDoesNotStartTraces() {
        assertThat(decision(Context.root(), SpanKind.INTERNAL)).isEqualTo(SamplingDecision.DROP);
        assertThat(decision(Context.root(), SpanKind.CLIENT)).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void workInsideARequestStaysInItsTrace() {
        Context parent = Context.root().with(Span.wrap(SpanContext.create(TRACE, "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault())));

        assertThat(decision(parent, SpanKind.CLIENT)).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
    }

    private SamplingDecision decision(Context parent, SpanKind kind) {
        return sampler.shouldSample(parent, TRACE, "span", kind, Attributes.empty(), List.of()).getDecision();
    }
}
