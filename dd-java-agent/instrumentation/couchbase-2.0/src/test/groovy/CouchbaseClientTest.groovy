// Modified by SignalFx
import com.couchbase.client.java.Bucket
import com.couchbase.client.java.document.JsonDocument
import com.couchbase.client.java.document.json.JsonObject
import com.couchbase.client.java.query.N1qlQuery
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import util.AbstractCouchbaseTest

class CouchbaseClientTest extends AbstractCouchbaseTest {

  def "test client #type"() {
    when:
    manager.hasBucket(bucketSettings.name())

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "couchbase"
          operationName "ClusterManager.hasBucket"
          errored false
          parent()
          tags {
            "$DDTags.SPAN_TYPE" DDSpanTypes.COUCHBASE
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    // Connect to the bucket and open it
    Bucket bkt = cluster.openBucket(bucketSettings.name(), bucketSettings.password())

    // Create a JSON document and store it with the ID "helloworld"
    JsonObject content = JsonObject.create().put("hello", "world")
    def inserted = bkt.upsert(JsonDocument.create("helloworld", content))

    then:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "couchbase"
          operationName "Bucket.upsert"
          errored false
          parent()
          tags {
            "$DDTags.SPAN_TYPE" DDSpanTypes.COUCHBASE
            "bucket" bkt.name()
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    def found = bkt.get("helloworld")

    then:
    found == inserted
    found.content().getString("hello") == "world"

    and:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "couchbase"
          operationName "Bucket.get"
          errored false
          parent()
          tags {
            "$DDTags.SPAN_TYPE" DDSpanTypes.COUCHBASE
            "bucket" bkt.name()
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()


    where:
    manager          | cluster          | bucketSettings
    couchbaseManager | couchbaseCluster | bucketCouchbase
    memcacheManager  | memcacheCluster  | bucketMemcache

    type = bucketSettings.type().name()
  }

  def "test query"() {
    setup:
    Bucket bkt = cluster.openBucket(bucketSettings.name(), bucketSettings.password())

    when:
    // Mock expects this specific query.
    // See com.couchbase.mock.http.query.QueryServer.handleString.
    def result = bkt.query(N1qlQuery.simple("SELECT mockrow"))

    then:
    result.parseSuccess()
    result.finalSuccess()
    result.first().value().get("row") == "value"

    and:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          serviceName "couchbase"
          operationName "Bucket.query"
          errored false
          parent()
          tags {
            "$DDTags.SPAN_TYPE" DDSpanTypes.COUCHBASE
            "bucket" bkt.name()
            defaultTags()
          }
        }
      }
    }

    where:
    manager          | cluster          | bucketSettings
    couchbaseManager | couchbaseCluster | bucketCouchbase
    // Only couchbase buckets support queries.

    type = bucketSettings.type().name()
  }
}
