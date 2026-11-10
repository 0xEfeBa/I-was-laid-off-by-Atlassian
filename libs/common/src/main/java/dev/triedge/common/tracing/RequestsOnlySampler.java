package dev.triedge.common.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;

public class RequestsOnlySampler implements Sampler {

    private final Sampler delegate;

    public RequestsOnlySampler(Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
            Attributes attributes, List<LinkData> parentLinks) {
        if (spanKind != SpanKind.SERVER && !Span.fromContext(parentContext).getSpanContext().isValid()) {
            return SamplingResult.drop();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "RequestsOnly{" + delegate.getDescription() + "}";
    }
}
