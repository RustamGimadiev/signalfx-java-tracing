package datadog.trace.instrumentation.jetty6;

import static io.opentracing.log.Fields.ERROR_OBJECT;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import org.mortbay.jetty.HttpConnection;
import org.mortbay.jetty.Response;

public class JettyHandlerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Scope startSpan(
      @Advice.This final Object source, @Advice.Argument(1) final HttpServletRequest req) {

    if (GlobalTracer.get().activeSpan() != null) {
      // Tracing might already be applied.  If so ignore this.
      return null;
    }

    final SpanContext extractedContext =
        GlobalTracer.get()
            .extract(Format.Builtin.HTTP_HEADERS, new HttpServletRequestExtractAdapter(req));
    final String resourceName = req.getMethod() + " " + source.getClass().getName();
    final Scope scope =
        GlobalTracer.get()
            .buildSpan("jetty.request")
            .asChildOf(extractedContext)
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
            .withTag(DDTags.SPAN_TYPE, DDSpanTypes.HTTP_SERVER)
            .withTag("servlet.context", req.getContextPath())
            .withTag("span.origin.type", source.getClass().getName())
            .startActive(false);

    if (scope instanceof TraceScope) {
      ((TraceScope) scope).setAsyncPropagation(true);
    }

    final Span span = scope.span();
    Tags.COMPONENT.set(span, "jetty-handler");
    Tags.HTTP_METHOD.set(span, req.getMethod());
    span.setTag(DDTags.RESOURCE_NAME, resourceName);
    Tags.HTTP_URL.set(span, req.getRequestURL().toString());
    if (req.getUserPrincipal() != null) {
      span.setTag(DDTags.USER_NAME, req.getUserPrincipal().getName());
    }
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(1) final HttpServletRequest req,
      @Advice.Argument(2) final HttpServletResponse resp,
      @Advice.Enter final Scope scope,
      @Advice.Thrown final Throwable throwable) {

    if (scope != null) {
      final Span span = scope.span();

      if (throwable != null) {
        Tags.ERROR.set(span, Boolean.TRUE);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
      } else {
        Response response = HttpConnection.getCurrentConnection().getResponse();
        Tags.HTTP_STATUS.set(span, response.getStatus());
      }

      if (scope instanceof TraceScope) {
        ((TraceScope) scope).setAsyncPropagation(false);
      }

      scope.close();
      span.finish(); // Finish the span manually since finishSpanOnClose was false
    }
  }
}
