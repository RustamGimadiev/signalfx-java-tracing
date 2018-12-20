// Modified by SignalFx
package springdata

import com.couchbase.client.java.view.DefaultView
import com.couchbase.client.java.view.DesignDocument
import io.opentracing.tag.Tags
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.repository.CrudRepository
import spock.lang.Shared
import util.AbstractCouchbaseTest

class CouchbaseSpringRepositoryTest extends AbstractCouchbaseTest {
  private static final Closure<Doc> FIND
  static {
    // This method is different in Spring Data 2+
    try {
      CrudRepository.getMethod("findOne", Serializable)
      FIND = { DocRepository repo, String id ->
        repo.findOne(id)
      }
    } catch (NoSuchMethodException e) {
      FIND = { DocRepository repo, String id ->
        repo.findById(id).get()
      }
    }
  }
  @Shared
  ConfigurableApplicationContext applicationContext
  @Shared
  DocRepository repo

  def setupSpec() {

    // Create view for SpringRepository's findAll()
    couchbaseCluster.openBucket(bucketCouchbase.name(), bucketCouchbase.password()).bucketManager()
      .insertDesignDocument(
      DesignDocument.create("doc", Collections.singletonList(DefaultView.create("all",
        '''
          function (doc, meta) {
             if (doc._class == "springdata.Doc") {
               emit(meta.id, null);
             }
          }
        '''.stripIndent()
      )))
    )
    CouchbaseConfig.setEnvironment(couchbaseEnvironment)
    CouchbaseConfig.setBucketSettings(bucketCouchbase)

    // Close all buckets and disconnect
    couchbaseCluster.disconnect()

    applicationContext = new AnnotationConfigApplicationContext(CouchbaseConfig)
    repo = applicationContext.getBean(DocRepository)
  }

  def cleanupSpec() {
    applicationContext.close()
  }

  def setup() {
    repo.deleteAll()
    TEST_WRITER.waitForTraces(1) // There might be more if there were documents to delete
    TEST_WRITER.clear()
  }

  def "test empty repo"() {
    when:
    def result = repo.findAll()

    then:
    !result.iterator().hasNext()

    and:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "Bucket.query"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }

  def "test CRUD"() {
    when:
    def doc = new Doc()

    then: // CREATE
    repo.save(doc) == doc

    and:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "Bucket.upsert"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    and: // RETRIEVE
    FIND(repo, "1") == doc

    and:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          operationName "Bucket.get"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    doc.data = "other data"

    then: // UPDATE
    repo.save(doc) == doc
    repo.findAll().asList() == [doc]

    assertTraces(3) {
      trace(0, 1) {
        span(0) {
          operationName "Bucket.upsert"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          operationName "Bucket.query"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
      trace(2, 1) {
        span(0) {
          operationName "Bucket.get"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when: // DELETE
    repo.delete("1")

    then:
    !repo.findAll().iterator().hasNext()

    and:
    assertTraces(2) {
      trace(0, 1) {
        span(0) {
          operationName "Bucket.remove"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
      trace(1, 1) {
        span(0) {
          operationName "Bucket.query"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT.key" "couchbase-client"
            "bucket" bucketCouchbase.name()
            defaultTags()
          }
        }
      }
    }
  }
}
