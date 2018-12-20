// Modified by SignalFx
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TestUtils
import datadog.trace.instrumentation.http_url_connection.UrlInstrumentation
import io.opentracing.tag.Tags
import io.opentracing.util.GlobalTracer

import static datadog.trace.agent.test.TestUtils.runUnderTrace
import static datadog.trace.instrumentation.http_url_connection.HttpUrlConnectionInstrumentation.HttpUrlState.COMPONENT_NAME
import static datadog.trace.instrumentation.http_url_connection.HttpUrlConnectionInstrumentation.HttpUrlState.OPERATION_NAME

class UrlConnectionTest extends AgentTestRunner {

  private static final int INVALID_PORT = TestUtils.randomOpenPort()

  def "trace request with connection failure #scheme"() {
    when:
    runUnderTrace("someTrace") {
      URLConnection connection = url.openConnection()
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(10000)
      assert GlobalTracer.get().scopeManager().active() != null
      connection.inputStream
    }

    then:
    thrown ConnectException

    expect:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags ConnectException, String
            defaultTags()
          }
        }
        span(1) {
          operationName OPERATION_NAME
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT.key" COMPONENT_NAME
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL.key" "$url"
            "$Tags.HTTP_METHOD.key" "GET"
            "$Tags.PEER_HOSTNAME.key" "localhost"
            "$Tags.PEER_PORT.key" INVALID_PORT
            errorTags ConnectException, String
            defaultTags()
          }
        }
      }
    }

    where:
    scheme  | _
    "http"  | _
    "https" | _

    url = new URI("$scheme://localhost:$INVALID_PORT").toURL()
  }

  def "trace request with connection failure to a local file with broken url path"() {
    setup:
    def url = new URI("file:/some-random-file%abc").toURL()

    when:
    runUnderTrace("someTrace") {
      url.openConnection()
    }

    then:
    thrown IllegalArgumentException

    expect:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags IllegalArgumentException, String
            defaultTags()
          }
        }
        span(1) {
          operationName "file.request"
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT.key" UrlInstrumentation.COMPONENT
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL.key" "$url"
            "$Tags.PEER_PORT.key" 80
            "$Tags.PEER_HOSTNAME.key" ""
            errorTags IllegalArgumentException, String
            defaultTags()
          }
        }
      }
    }
  }
}
