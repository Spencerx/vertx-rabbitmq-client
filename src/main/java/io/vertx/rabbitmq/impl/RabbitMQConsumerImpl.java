package io.vertx.rabbitmq.impl;

import io.vertx.core.*;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.streams.impl.InboundBuffer;
import io.vertx.rabbitmq.QueueOptions;
import io.vertx.rabbitmq.RabbitMQConsumer;
import io.vertx.rabbitmq.RabbitMQMessage;

import java.io.IOException;

/**
 * A implementation of {@link RabbitMQConsumer}
 */
public class RabbitMQConsumerImpl implements RabbitMQConsumer {

  private static final Logger log = LoggerFactory.getLogger(RabbitMQConsumerImpl.class);

  private Handler<Throwable> exceptionHandler;
  private Handler<Void> endHandler;
  private String queueName;
  private final QueueConsumerHandler consumerHandler;
  private final boolean keepMostRecent;
  private final InboundBuffer<RabbitMQMessage> pending;
  private final int maxQueueSize;
  private volatile boolean cancelled;

  RabbitMQConsumerImpl(Context context, QueueConsumerHandler consumerHandler, QueueOptions options, String queueName) {
    this.consumerHandler = consumerHandler;
    this.keepMostRecent = options.isKeepMostRecent();
    this.maxQueueSize = options.maxInternalQueueSize();
    this.pending = new InboundBuffer<RabbitMQMessage>(context, maxQueueSize).pause();
    this.queueName = queueName;
  }

  @Override
  public String queueName() {
    return queueName;
  }

  @Override
  public RabbitMQConsumer setQueueName(String name) {
    this.queueName = name;
    return this;
  }

  @Override
  public RabbitMQConsumer exceptionHandler(Handler<Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
    return this;
  }

  @Override
  public RabbitMQConsumer handler(Handler<RabbitMQMessage> handler) {
    if (handler != null) {
      pending.handler(msg -> {
        try {
          handler.handle(msg);
        } catch (Exception e) {
          handleException(e);
        }
      });
    } else {
      pending.handler(null);
    }
    return this;
  }

  @Override
  public RabbitMQConsumer pause() {
    pending.pause();
    return this;
  }

  @Override
  public RabbitMQConsumer resume() {
    pending.resume();
    return this;
  }

  @Override
  public RabbitMQConsumer fetch(long amount) {
    pending.fetch(amount);
    return this;
  }

  @Override
  public RabbitMQConsumer endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  @Override
  public String consumerTag() {
    return consumerHandler.getConsumerTag();
  }

  @Override
  public Future<Void> cancel() {
    Future<Void> operationResult;
    try {
      log.debug("Cancelling " + consumerTag());
      cancelled = true;
      consumerHandler.getChannel().basicCancel(consumerTag());
      operationResult = Future.succeededFuture();
    } catch (IOException e) {
      operationResult = Future.failedFuture(e);
    }
    handleEnd();
    return operationResult;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public boolean isPaused() {
    return false;
  }

  /**
   * Push message to stream.
   * <p>
   * Should be called from a vertx thread.
   *
   * @param message received message to deliver
   */
  void handleMessage(RabbitMQMessage message) {

    if (pending.size() >= maxQueueSize) {
      if (keepMostRecent) {
        pending.read();
      } else {
        log.debug("Discard a received message since stream is paused and buffer flag is false");
        return;
      }
    }
    pending.write(message);
  }

  /**
   * Trigger exception handler with given exception
   */
  private void handleException(Throwable exception) {
    if (exceptionHandler != null) {
      exceptionHandler.handle(exception);
    }
  }

  /**
   * Trigger end of stream handler
   */
  void handleEnd() {
    if (endHandler != null) {
      endHandler.handle(null);
    }
  }
}
