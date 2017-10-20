package com.hubspot.smtp.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Creates {@link SmtpSession} instances by connecting to remote servers.
 * <p>
 * <p>This class is thread-safe.
 */
public class SmtpSessionFactory implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpSessionFactory.class);

  private final ChannelGroup allChannels;
  private final SmtpSessionFactoryConfig factoryConfig;
  private Bootstrap bootstrap;
  private SmtpSessionConfig config;
  private ResponseHandler responseHandler;
  public static final AttributeKey<String> CHANNEL_KEY = AttributeKey.valueOf("sm-smtp-channel-id");

  /**
   * Creates a new factory with the provided configuration.
   */
  public SmtpSessionFactory(SmtpSessionFactoryConfig factoryConfig, SmtpSessionConfig config) {
    this.factoryConfig = factoryConfig;
    this.config = config;
    allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    responseHandler = new ResponseHandler(config.getReadTimeout(), config.getExceptionHandler());
    bootstrap = new Bootstrap()
            .group(factoryConfig.getEventLoopGroup())
            .channel(factoryConfig.getChannelClass())
            .option(ChannelOption.ALLOCATOR, factoryConfig.getAllocator())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectionTimeout().toMillis())
            .option(ChannelOption.SO_KEEPALIVE, true)
            .remoteAddress(config.getRemoteAddress())
            .localAddress(config.getLocalAddress().orElse(null))
            .handler(new Initializer(responseHandler, config));
  }

  /**
   * Connects to a remote server.
   *
   * @return a future representing the initial response from the server
   */
  public CompletableFuture<SmtpClientResponse> connect() {
    CompletableFuture<SmtpClientResponse> connectFuture = new CompletableFuture<>();
    LOG.info("{} Connect: ", Thread.currentThread().getName());
    ChannelFuture channelFuture = bootstrap.connect();
    channelFuture.addListener(f -> {
      if (f.isSuccess()) {
        Channel channel = ((ChannelFuture) f).channel();
        LOG.info("Connected: {}", channel);
        channel.attr(CHANNEL_KEY).set(channel.toString());
        allChannels.add(channel);
        CompletableFuture<List<SmtpResponse>> initialResponseFuture = responseHandler.createResponseFuture(channel.attr(CHANNEL_KEY).get(), 1, config.getInitialResponseReadTimeout(), () -> "initial response");
        SmtpSession session = new SmtpSession(channel, responseHandler, config, factoryConfig.getExecutor(), factoryConfig.getSslEngineSupplier());
        initialResponseFuture.handleAsync((rs, e) -> {
          if (e != null) {
            session.close();
            connectFuture.completeExceptionally(e);
          } else {
            connectFuture.complete(new SmtpClientResponse(session, rs.get(0)));
          }

          return null;
        }, factoryConfig.getExecutor());

      } else {
        factoryConfig.getExecutor().execute(() -> connectFuture.completeExceptionally(f.cause()));
      }
    });
    channelFuture.channel().closeFuture().addListener(future -> {
      LOG.info("{} Channel closed: {}", Thread.currentThread().getName(), ((ChannelFuture) future).channel());
      LOG.info("Cause of: {}", future.cause());
    });
    return connectFuture;
  }

  @Override
  public void close() throws IOException {
    try {
      allChannels.close().await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Closes all sessions created by this factory.
   *
   * @return a future that will be completed when all sessions have been closed
   */
  @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
  public CompletableFuture<Void> closeAsync() {
    CompletableFuture<Void> returnedFuture = new CompletableFuture<>();

    allChannels.close().addListener(f -> {
      if (f.isSuccess()) {
        returnedFuture.complete(null);
      } else {
        returnedFuture.completeExceptionally(f.cause());
      }
    });

    return returnedFuture;
  }
}
