package com.hubspot.smtp.client;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.Attribute;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.hubspot.smtp.client.SmtpSessionFactory.CHANNEL_KEY;

/**
 * A Netty handler that collects responses to SMTP commands and makes them available.
 */
@ChannelHandler.Sharable
class ResponseHandler extends SimpleChannelInboundHandler<SmtpResponse> {
    private static final Logger LOG = LoggerFactory.getLogger(ResponseHandler.class);
    private static final HashedWheelTimer TIMER = new HashedWheelTimer(new ThreadFactoryBuilder().setDaemon(true).setNameFormat("response-timer-%d").build());
    private final Map<String, ResponseCollector> responseCollector = new ConcurrentHashMap<>();
    private final Optional<Duration> defaultResponseTimeout;
    private final Optional<Consumer<Throwable>> exceptionHandler;

    ResponseHandler(Optional<Duration> defaultResponseTimeout, Optional<Consumer<Throwable>> exceptionHandler) {
        this.defaultResponseTimeout = defaultResponseTimeout;
        this.exceptionHandler = exceptionHandler;
    }

    CompletableFuture<List<SmtpResponse>> createResponseFuture(String channelId, int expectedResponses, Supplier<String> debugStringSupplier) {
        return createResponseFuture(channelId, expectedResponses, defaultResponseTimeout, debugStringSupplier);
    }

    CompletableFuture<List<SmtpResponse>> createResponseFuture(String channelId, int expectedResponses, Optional<Duration> responseTimeout, Supplier<String> debugStringSupplier) {
        ResponseCollector collector = new ResponseCollector(expectedResponses, debugStringSupplier);
        responseCollector.put(channelId, collector);
        CompletableFuture<List<SmtpResponse>> responseFuture = collector.getFuture();

        applyResponseTimeout(channelId, responseFuture, responseTimeout, debugStringSupplier);

        return responseFuture;
    }

    private void applyResponseTimeout(String connectionId, CompletableFuture<List<SmtpResponse>> responseFuture, Optional<Duration> responseTimeout, Supplier<String> debugStringSupplier) {
        responseTimeout = responseTimeout.isPresent() ? responseTimeout : defaultResponseTimeout;

        responseTimeout.ifPresent(timeout -> {
            Timeout hwtTimeout = TIMER.newTimeout(ignored -> {
                String message = String.format("[%s] Timed out waiting for a response to [%s]",
                        connectionId, debugStringSupplier.get());

                responseFuture.completeExceptionally(new TimeoutException(message));
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            responseFuture.whenComplete((ignored1, ignored2) -> hwtTimeout.cancel());
        });
    }

    Optional<String> getPendingResponseDebugString(String channelId) {
        return Optional.ofNullable(this.responseCollector.get(channelId)).map(ResponseCollector::getDebugString);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SmtpResponse msg) throws Exception {
        String key = Optional
                .ofNullable(ctx).map(ChannelHandlerContext::channel)
                .map(c -> c.attr(CHANNEL_KEY))
                .map(Attribute::get)
                .map(String::toString).orElse("");
        ResponseCollector collector = responseCollector.get(key);
        if (collector == null) {
            LOG.warn("[{}] Unexpected response received: {}", key, msg);
        } else {
            LOG.info("{} Received response from channel {} is: {}", Thread.currentThread().getName(), ctx.channel(), msg);
            boolean complete = collector.addResponse(msg);
            if (complete) {
                // because only the event loop code sets this field when it is non-null,
                // and because channelRead is always run in the same thread, we can
                // be sure this value hasn't changed since we read it earlier in this method
                responseCollector.remove(key);
                collector.complete();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof ReadTimeoutException) {
            LOG.warn("[{}] The channel was closed because a read timed out", ctx.channel().attr(CHANNEL_KEY).get());
        }
        String key = Optional
                .ofNullable(ctx).map(ChannelHandlerContext::channel)
                .map(c -> c.attr(CHANNEL_KEY))
                .map(Attribute::get)
                .map(String::toString).orElse("");
        ResponseCollector collector = responseCollector.remove(key);
        if (collector != null) {
            collector.completeExceptionally(cause);
        } else {
            // this exception can't get back to the client via a future,
            // use the connection exception handler if possible
            if (exceptionHandler.isPresent()) {
                exceptionHandler.get().accept(cause);
            } else {
                super.exceptionCaught(ctx, cause);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String key = Optional
                .ofNullable(ctx).map(ChannelHandlerContext::channel)
                .map(c -> c.attr(CHANNEL_KEY))
                .map(Attribute::get)
                .map(String::toString).orElse("");
        ResponseCollector collector = responseCollector.get(key);
        if (collector != null) {
            collector.completeExceptionally(new ChannelClosedException(key, "Handled channelInactive while waiting for a response to [" + collector.getDebugString() + "]"));
        }

        super.channelInactive(ctx);
    }
}
