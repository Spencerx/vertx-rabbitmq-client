package io.vertx.tests.rabbitmq;

import com.rabbitmq.client.AMQP;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.ext.unit.TestContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vertx.rabbitmq.*;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.ClassRule;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;

/**
 * The point of this test is to demonstrate what happens with a transient queue when reconnects (or auto recovery) is used.
 * If, when the server comes back, there exchange exists but the queue does not then messages will be dropped.
 * This is inevitable - it is not possible to guarantee that the consumer (that creates the queue) starts before the publisher (that creates the exchange).
 * The test does guarantee that all messages will be processed by the broker, but if that processing discards the message there is nothing that can be done.
 */
public class RabbitMQClientTransientQueueTest extends RabbitMQClientTestBase {

  @SuppressWarnings("constantname")
  private static final Logger logger = LoggerFactory.getLogger(RabbitMQClientTransientQueueTest.class);

  private static final String EXCHANGE_NAME = "RabbitMQClientTransientQueueTest";

  private String queueName;

  private static final int getFreePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch(IOException ex) {
      return -1;
    }
  }

  @ClassRule
  public static final GenericContainer fixedRabbitmq = new FixedHostPortGenericContainer<>("rabbitmq:3.7")
    .withCreateContainerCmdModifier(cmd -> cmd.withHostName("bouncing-rabbit2"))
    .withFixedExposedPort(getFreePort(), 5672);

  @Override
  public RabbitMQOptions config() throws Exception {
    RabbitMQOptions options = super.config();
    options.setUri("amqp://" + fixedRabbitmq.getContainerIpAddress() + ":" + fixedRabbitmq.getMappedPort(5672));
    options.setAutomaticRecoveryEnabled(false);
    options.setReconnectAttempts(Integer.MAX_VALUE);
    options.setReconnectInterval(500);
    return options;
  }

  static class MessageDefinition {
    final int i;
    final String messageId;
    final String messageBody;

    public MessageDefinition(int i, String messageId, String messageBody) {
      this.i = i;
      this.messageId = messageId;
      this.messageBody = messageBody;
    }

  }

  @Test
  public void testStopEmpty(TestContext ctx) throws Throwable {

    this.client = RabbitMQClient.create(vertx, config());

    RabbitMQPublisher publisher = RabbitMQPublisher.create(vertx
        , client
        , new RabbitMQPublisherOptions()
            .setReconnectAttempts(Integer.MAX_VALUE)
            .setReconnectInterval(100)
            .setMaxInternalQueueSize(Integer.MAX_VALUE)
    );
    CompletableFuture startLatch = new CompletableFuture();
    publisher.start().onComplete(ar -> {
      startLatch.complete(null);
    });
    startLatch.get();

    CompletableFuture stopLatch = new CompletableFuture();
    publisher.stop().onComplete(ar -> {
      stopLatch.complete(null);
    });
    stopLatch.get();

  }

  @Test
  public void testPublishOverReconnect(TestContext ctx) throws Throwable {

    int count = 1000;

    Map<String, MessageDefinition> messages = new HashMap<>(count);
    for (int i = 0; i < count; ++i) {
      String messageId = Integer.toString(i);
      messages.put(messageId, new MessageDefinition(i, messageId, "Message " + i));
    }
    Map<String, RabbitMQMessage> messagesReceived = new HashMap<>(count);

    CompletableFuture<Void> receivedLastMessageLatch = new CompletableFuture<>();

    // Now that publishers start asynchronously there is a race condition where messages can be published
    // before the connection established callbacks have run, which means that the queue doesn't exist and
    // messages get lost.
    // Unfortunately, with transient queues that is inevitable, consumers must ensure their state is correct
    // at the point that they create their binding.
    CompletableFuture<Void> letConsumerStartFirst = new CompletableFuture<>();

    AtomicReference<RabbitMQConsumer> consumer = new AtomicReference<>();
    RabbitMQClient consumerClient = RabbitMQClient.create(vertx, config());
    AtomicLong duplicateCount = new AtomicLong();
    prepareClient(consumerClient
        , p -> {
          consumerClient.exchangeDeclare(EXCHANGE_NAME, "fanout", true, false).onComplete(ar1 -> {
            if (ar1.succeeded()) {
              consumerClient.queueDeclare("", false, true, true).onComplete(ar2 -> {
                if (ar2.succeeded()) {
                  queueName = ar2.result().getQueue();
                  RabbitMQConsumer currentConsumer = consumer.get();
                  if (currentConsumer != null) {
                    currentConsumer.setQueueName(queueName);
                  }
                  consumerClient.queueBind(queueName, EXCHANGE_NAME, "").onComplete(ar3 -> {
                    if (ar3.succeeded()) {
                      p.complete();
                    } else {
                      p.fail(ar3.cause());
                    }
                  });
                } else {
                  p.fail(ar2.cause());
                }
              });
            } else {
              p.fail(ar1.cause());
            }
          });
        }
        , p -> {
          consumerClient.basicConsumer(queueName
                  , new QueueOptions().setAutoAck(false)
          ).onComplete(ar4 -> {
            if (ar4.succeeded()) {
              letConsumerStartFirst.complete(null);
              consumer.set(ar4.result());
              ar4.result().handler(m -> {
                synchronized(messages) {
                  Object prev = messagesReceived.put(m.properties().getMessageId(), m);
                  if (prev != null) {
                    duplicateCount.incrementAndGet();
                  }
                  // logger.info("Have received " + messagesReceived.size() + " messages with " + duplicateCount.get() + " duplicates");
                  // logger.info("Have received " + messagesReceived.size() + " messages");
                  // List<String> got = new ArrayList<>(messagesReceived.keySet());
                  // Collections.sort(got);
                  // logger.info("Received " + got);
                  consumerClient.basicAck(m.envelope().getDeliveryTag(), false);
                  if (Integer.toString(count - 1).equals(m.properties().getMessageId())) {
                    logger.info("Have received \"" + m.body().toString() + "\" the last message, with " + duplicateCount.get() + " duplicates");
                    receivedLastMessageLatch.complete(null);
                  }
                }
              });
              p.complete(null);
            } else {
              p.completeExceptionally(ar4.cause());
            }
          });
        }
    );

    letConsumerStartFirst.get(2, TimeUnit.MINUTES);
    logger.info("Consumer started, preparing producer");

    this.client = RabbitMQClient.create(vertx, config());
    prepareClient(client
        , p -> {
          client.exchangeDeclare(EXCHANGE_NAME, "fanout", true, false).onComplete(ar1 -> {
            if (ar1.succeeded()) {
              p.complete();
            } else {
              p.fail(ar1.cause());
            }
          });
        }
        , null
    );

    vertx.setTimer(200, l -> {
      vertx.executeBlocking(() -> {
        logger.info("Stopping rabbitmq container");
        fixedRabbitmq.stop();
        logger.info("Starting rabbitmq container");
        fixedRabbitmq.start();
        logger.info("Started rabbitmq container");
        return null;
      });
    });

    RabbitMQPublisher publisher = RabbitMQPublisher.create(vertx
        , client
        , new RabbitMQPublisherOptions()
            .setReconnectAttempts(Integer.MAX_VALUE)
            .setReconnectInterval(100)
            .setMaxInternalQueueSize(Integer.MAX_VALUE)
    );
    publisher.start().onComplete(ar -> {
      publisher.getConfirmationStream().handler(c -> {
        synchronized(messages) {
          messages.remove(c.getMessageId());
        }
      });
    });

    List<MessageDefinition> messagesCopy;
    synchronized(messages) {
      messagesCopy = new ArrayList<>(messages.values());
      Collections.sort(messagesCopy, (l,r) -> l.messageId.compareTo(r.messageId));
    }
    for (MessageDefinition message : messagesCopy) {
      Thread.sleep(4);
      publisher.publish(EXCHANGE_NAME
          , ""
          , new AMQP.BasicProperties.Builder()
              .messageId(message.messageId)
              .build()
          , Buffer.buffer(message.messageBody));
    }
    logger.info("Still got " + publisher.queueSize() + " messages in the send queue, waiting for that to clear");
    CompletableFuture<Void> emptyPublisherLatch = new CompletableFuture<>();
    publisher.stop().onComplete(ar -> {
      if (ar.succeeded()) {
        emptyPublisherLatch.complete(null);
      } else {
        emptyPublisherLatch.completeExceptionally(ar.cause());
      }
    });
    emptyPublisherLatch.get();
    publisher.restart();

    synchronized(messages) {
      logger.info("After the publisher has sent everything there remain " + messages.size() + " messages unconfirmed (" + messages.keySet() + ")");
      messagesCopy = new ArrayList<>(messages.values());
      Collections.sort(messagesCopy, (l,r) -> l.messageId.compareTo(r.messageId));
    }
    for (MessageDefinition message : messagesCopy) {
      Thread.sleep(4);
      publisher.publish(EXCHANGE_NAME
          , ""
          , new AMQP.BasicProperties.Builder()
              .messageId(message.messageId)
              .build()
          , Buffer.buffer(message.messageBody));
    }

    logger.info("Waiting up to 20s for the last message to be received (noting that it may not be)");
    try {
      receivedLastMessageLatch.get(20, TimeUnit.SECONDS);
      logger.info("Latched, shutting down");
    } catch(TimeoutException ex) {
      logger.info("Shutting down after waiting for last message that isn't going to arrive");
    }

    List<String> got = new ArrayList<>(messagesReceived.keySet());
    analyzeReceivedMessages(got);

    logger.info("Shutting down");
    consumer.get().cancel().onComplete(ar1 -> {
      logger.info("Consumer cancelled");
      consumerClient.stop().onComplete(ar2 -> {
        logger.info("Consumer client stopped");
        client.stop().onComplete(ar3 -> {
          logger.info("Producer client stopped");
          ctx.async().complete();
        });
      });
    });
  }

  static void prepareClient(
      RabbitMQClient client
      , Handler<Promise<Void>> connectionEstablishedCallback
      , Handler<CompletableFuture<Void>> startHandler
  ) throws InterruptedException, Exception, TimeoutException, ExecutionException {
    if (connectionEstablishedCallback != null) {
      client.addConnectionEstablishedCallback(connectionEstablishedCallback);
    }
    CompletableFuture<Void> latch = new CompletableFuture<>();
    client.start().onComplete(ar -> {
      if (ar.succeeded()) {
        if (startHandler != null) {
          startHandler.handle(latch);
        } else {
          latch.complete(null);
        }
      } else {
        latch.completeExceptionally(ar.cause());
      }
    });
    latch.get(100L, TimeUnit.SECONDS);
  }

  private static class Gap {
    public final int from;
    public final int to;

    public Gap(int from, int to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public String toString() {
      return "[" + from + '-' + to + ']';
    }

  };

  private void analyzeReceivedMessages(List<String> received) {
    int values[] = received.stream().mapToInt(v -> Integer.parseInt(v)).sorted().toArray();
    // logger.info("Received messages: " + Arrays.toString(values));
    List<Gap> gaps = new ArrayList<>();
    for (int idx = 0; idx < values.length - 1; ++idx) {
      if (values[idx] != values[idx + 1] - 1) {
        gaps.add(new Gap(values[idx], values[idx + 1]));
      }
    }
    logger.info("Received messages had " + gaps.size() + " gaps (expected one): " + gaps);
  }

}
