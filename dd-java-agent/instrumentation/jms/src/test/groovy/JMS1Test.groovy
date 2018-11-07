// Modified by SignalFx
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import io.opentracing.tag.Tags
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.ActiveMQMessageConsumer
import org.apache.activemq.ActiveMQMessageProducer
import org.apache.activemq.command.ActiveMQTextMessage
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.Session
import javax.jms.TextMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class JMS1Test extends AgentTestRunner {
  @Shared
  String messageText = "a message"
  @Shared
  Session session

  ActiveMQTextMessage message = session.createTextMessage(messageText)

  def setupSpec() {
    EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()
    broker.start()
    final ActiveMQConnectionFactory connectionFactory = broker.createConnectionFactory()

    final Connection connection = connectionFactory.createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  }

  def "sending a message to #jmsResourceName generates spans"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.send(message)

    TextMessage receivedMessage = consumer.receive()

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, 0, jmsResourceName)
      trace(1, 1) { // Consumer trace
        span(0) {
          childOf TEST_WRITER.firstTrace().get(2)
          serviceName "jms"
          operationName "Consumed from $jmsResourceName"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          errored false

          tags {
            defaultTags(true)
            "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_CONSUMER
            "${Tags.COMPONENT.key}" "jms"
            "${Tags.SPAN_KIND.key}" "consumer"
            "span.origin.type" ActiveMQMessageConsumer.name
          }
        }
      }
    }

    cleanup:
    producer.close()
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "sending to a MessageListener on #jmsResourceName generates a span"() {
    setup:
    def lock = new CountDownLatch(1)
    def messageRef = new AtomicReference<TextMessage>()
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)
    consumer.setMessageListener new MessageListener() {
      @Override
      void onMessage(Message message) {
        lock.await() // ensure the producer trace is reported first.
        messageRef.set(message)
      }
    }

    producer.send(message)
    lock.countDown()

    expect:
    assertTraces(2) {
      producerTrace(it, 0, jmsResourceName)
      trace(1, 1) { // Consumer trace
        span(0) {
          childOf TEST_WRITER.firstTrace().get(2)
          serviceName "jms"
          operationName "Received from $jmsResourceName"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          errored false

          tags {
            defaultTags(true)
            "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_CONSUMER
            "${Tags.COMPONENT.key}" "jms"
            "${Tags.SPAN_KIND.key}" "consumer"
            "span.origin.type" { t -> t.contains("JMS1Test") }
          }
        }
      }
    }
    // This check needs to go after all traces have been accounted for
    messageRef.get().text == messageText

    cleanup:
    producer.close()
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "failing to receive message with receiveNoWait on #jmsResourceName works"() {
    setup:
    def consumer = session.createConsumer(destination)

    // Receive with timeout
    TextMessage receivedMessage = consumer.receiveNoWait()

    expect:
    receivedMessage == null
    assertTraces(1) {
      trace(0, 1) { // Consumer trace
        span(0) {
          parent()
          serviceName "jms"
          operationName "JMS receiveNoWait"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          errored false

          tags {
            defaultTags()
            "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_CONSUMER
            "${Tags.COMPONENT.key}" "jms"
            "${Tags.SPAN_KIND.key}" "consumer"
            "span.origin.type" ActiveMQMessageConsumer.name
          }
        }
      }
    }

    cleanup:
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
  }

  def "failing to receive message with wait(timeout) on #jmsResourceName works"() {
    setup:
    def consumer = session.createConsumer(destination)

    // Receive with timeout
    TextMessage receivedMessage = consumer.receive(100)

    expect:
    receivedMessage == null
    assertTraces(1) {
      trace(0, 1) { // Consumer trace
        span(0) {
          parent()
          serviceName "jms"
          operationName "JMS receive"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          errored false

          tags {
            defaultTags()
            "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_CONSUMER
            "${Tags.COMPONENT.key}" "jms"
            "${Tags.SPAN_KIND.key}" "consumer"
            "span.origin.type" ActiveMQMessageConsumer.name
          }
        }
      }
    }

    cleanup:
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
  }

  def "sending a read-only message to #jmsResourceName fails"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    expect:
    !message.isReadOnlyProperties()

    when:
    message.setReadOnlyProperties(true)
    and:
    producer.send(message)

    TextMessage receivedMessage = consumer.receive()

    then:
    receivedMessage.text == messageText

    // This will result in a logged failure because we tried to
    // write properties in MessagePropertyTextMap when readOnlyProperties = true.
    // The consumer span will also not be linked to the parent.
    assertTraces(2) {
      producerTrace(it, 0, jmsResourceName)
      trace(1, 1) { // Consumer trace
        span(0) {
          parent()
          serviceName "jms"
          operationName "Consumed from $jmsResourceName"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          errored false

          tags {
            defaultTags()
            "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_CONSUMER
            "${Tags.COMPONENT.key}" "jms"
            "${Tags.SPAN_KIND.key}" "consumer"
            "span.origin.type" ActiveMQMessageConsumer.name
          }
        }
      }
    }

    cleanup:
    producer.close()
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def producerTrace(ListWriterAssert writer, int index, String jmsResourceName) {
    writer.trace(index, 3) {
      span(0) {
        parent()
        serviceName "jms"
        operationName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false

        tags {
          defaultTags()
          "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_PRODUCER
          "${Tags.COMPONENT.key}" "jms"
          "${Tags.SPAN_KIND.key}" "producer"
          "span.origin.type" ActiveMQMessageProducer.name
        }
      }
      span(1) {
        childOf span(0)
        serviceName "jms"
        operationName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false

        tags {
          defaultTags()
          "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_PRODUCER
          "${Tags.COMPONENT.key}" "jms"
          "${Tags.SPAN_KIND.key}" "producer"
          "span.origin.type" ActiveMQMessageProducer.name
        }
      }
      span(2) {
        childOf span(1)
        serviceName "jms"
        operationName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false

        tags {
          defaultTags()
          "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_PRODUCER
          "${Tags.COMPONENT.key}" "jms"
          "${Tags.SPAN_KIND.key}" "producer"
          "span.origin.type" ActiveMQMessageProducer.name
        }
      }
    }
  }

  def consumerTrace(ListWriterAssert writer, int index, String jmsResourceName, origin) {
    writer.trace(index, 1) {
      span(0) {
        childOf TEST_WRITER.firstTrace().get(2)
        serviceName "jms"
        operationName "Received from $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false

        tags {
          defaultTags()
          "${DDTags.SPAN_TYPE}" DDSpanTypes.MESSAGE_CONSUMER
          "${Tags.COMPONENT.key}" "jms"
          "${Tags.SPAN_KIND.key}" "consumer"
          "span.origin.type" origin
        }
      }
    }
  }
}
