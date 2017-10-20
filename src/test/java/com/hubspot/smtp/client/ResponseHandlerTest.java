package com.hubspot.smtp.client;

import com.google.common.collect.Lists;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.hubspot.smtp.client.SmtpSessionFactory.CHANNEL_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class ResponseHandlerTest {
  private static final DefaultSmtpResponse SMTP_RESPONSE = new DefaultSmtpResponse(250);
  private static final Supplier<String> DEBUG_STRING = () -> "debug";
  private static final String CONNECTION_ID = "connection#1";
  private static final String CONNECTION_ID_PREFIX = "[" + CONNECTION_ID + "] ";

  private ResponseHandler responseHandler;
  private ChannelHandlerContext context;

  @Before
  public void setup() {
    responseHandler = new ResponseHandler(Optional.empty(), Optional.empty());
    context = mock(ChannelHandlerContext.class);
    Channel channel = mock(Channel.class);
    Attribute attribute = mock(Attribute.class);
    when(attribute.get()).thenReturn("123");
    when(channel.attr(CHANNEL_KEY)).thenReturn(attribute);
    when(context.channel()).thenReturn(channel);
  }

  @Test
  public void itCompletesExceptionallyIfAnExceptionIsCaught() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(), 1, DEBUG_STRING);
    Exception testException = new Exception("test");

    responseHandler.exceptionCaught(context, testException);

    assertThat(f.isCompletedExceptionally()).isTrue();

    assertThat(catchThrowable(f::get).getCause())
        .isInstanceOf(ResponseException.class)
        .hasMessage("Received an exception while waiting for a response to [debug]; responses so far: <none>")
        .hasCause(testException);
  }

  @Test
  public void itCompletesWithAResponseWhenHandled() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);

    responseHandler.channelRead(context, SMTP_RESPONSE);

    assertThat(f.isCompletedExceptionally()).isFalse();
    assertThat(f.get()).isEqualTo(Lists.newArrayList(SMTP_RESPONSE));
  }

  @Test
  public void itDoesNotCompleteWhenSomeOtherObjectIsRead() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);

    responseHandler.channelRead(context, "unexpected");

    assertThat(f.isDone()).isFalse();
  }

  @Test
  @Ignore
  public void itOnlyCreatesOneResponseFutureAtATime() {
    assertThat(responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, () -> "old")).isNotNull();

    assertThatThrownBy(() -> responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, () -> "new"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(CONNECTION_ID_PREFIX + "Cannot wait for a response to [new] because we're still waiting for a response to [old]");
  }

  @Test
  @Ignore
  public void itOnlyCreatesOneResponseFutureAtATimeForMultipleResponses() {
    assertThat(responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),2, () -> "old")).isNotNull();

    assertThatThrownBy(() -> responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, () -> "new"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(CONNECTION_ID_PREFIX + "Cannot wait for a response to [new] because we're still waiting for a response to [old]");
  }

  @Test
  public void itCanCreateAFutureThatWaitsForMultipleReponses() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),3, DEBUG_STRING);
    SmtpResponse response1 = new DefaultSmtpResponse(250, "1");
    SmtpResponse response2 = new DefaultSmtpResponse(250, "2");
    SmtpResponse response3 = new DefaultSmtpResponse(250, "3");

    responseHandler.channelRead(context, response1);

    assertThat(f.isDone()).isFalse();

    responseHandler.channelRead(context, response2);
    responseHandler.channelRead(context, response3);

    assertThat(f.isDone()).isTrue();

    assertThat(f.isCompletedExceptionally()).isFalse();
    assertThat(f.get().get(0)).isEqualTo(response1);
    assertThat(f.get().get(1)).isEqualTo(response2);
    assertThat(f.get().get(2)).isEqualTo(response3);
  }

  @Test
  public void itCanCreateAFutureInTheCallbackForAPreviousFuture() throws Exception {
    CompletableFuture<List<SmtpResponse>> future = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);

    CompletableFuture<Void> assertion = future.thenRun(() -> assertThat(responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING)).isNotNull());

    responseHandler.channelRead(context, SMTP_RESPONSE);

    assertion.get();
  }

  @Test
  public void itCanFailMultipleResponseFuturesAtAnyTime() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),3, DEBUG_STRING);
    Exception testException = new Exception("test");

    responseHandler.exceptionCaught(context, testException);

    assertThat(f.isCompletedExceptionally()).isTrue();

    assertThat(catchThrowable(f::get).getCause())
        .isInstanceOf(ResponseException.class)
        .hasMessage("Received an exception while waiting for a response to [debug]; responses so far: <none>")
        .hasCause(testException);
  }

  @Test
  public void itCanCreateNewFuturesOnceAResponseHasArrived() throws Exception {
    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
    responseHandler.channelRead(context, SMTP_RESPONSE);

    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
  }

  @Test
  public void itCanCreateNewFuturesOnceATheExpectedResponsesHaveArrived() throws Exception {
    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),2, DEBUG_STRING);
    responseHandler.channelRead(context, SMTP_RESPONSE);
    responseHandler.channelRead(context, SMTP_RESPONSE);

    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
  }

  @Test
  public void itCanCreateNewFuturesOnceAnExceptionIsHandled() throws Exception {
    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
    responseHandler.exceptionCaught(context, new Exception("test"));

    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
  }

  @Test
  public void itCanTellWhenAResponseIsPending() {
    assertThat(responseHandler.getPendingResponseDebugString(context.channel().attr(CHANNEL_KEY).get())).isEmpty();

    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);

    assertThat(responseHandler.getPendingResponseDebugString(context.channel().attr(CHANNEL_KEY).get())).contains(DEBUG_STRING.get());
  }

  @Test
  @Ignore
  public void itCompletesExceptionallyIfTheChannelIsClosed() throws Exception {
    CompletableFuture<List<SmtpResponse>> f = responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);

    responseHandler.channelInactive(context);

    assertThat(f.isCompletedExceptionally()).isTrue();

    assertThat(catchThrowable(f::get).getCause())
        .isInstanceOf(ResponseException.class)
        .hasMessage("Received an exception while waiting for a response to [debug]; responses so far: <none>")
        .hasCauseInstanceOf(ChannelClosedException.class);
  }

  @Test
  public void itCompletesExceptionallyIfTheDefaultResponseTimeoutIsExceeded() throws Exception {
    ResponseHandler impatientHandler = new ResponseHandler(Optional.of(Duration.ofMillis(200)), Optional.empty());

    CompletableFuture<List<SmtpResponse>> responseFuture = impatientHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
    assertThat(responseFuture.isCompletedExceptionally()).isFalse();

    Thread.sleep(400);
    assertThat(responseFuture.isCompletedExceptionally()).isTrue();
  }

  @Test
  public void itCompletesExceptionallyIfTheResponseTimeoutIsExceeded() throws Exception {
    ResponseHandler impatientHandler = new ResponseHandler(Optional.of(Duration.ofDays(365)), Optional.empty());

    CompletableFuture<List<SmtpResponse>> responseFuture = impatientHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, Optional.of(Duration.ofMillis(200)), DEBUG_STRING);
    assertThat(responseFuture.isCompletedExceptionally()).isFalse();

    Thread.sleep(400);
    assertThat(responseFuture.isCompletedExceptionally()).isTrue();
  }

  @Test
  public void itPassesExceptionsToTheProvidedHandlerIfPresent() throws Exception {
    Consumer<Throwable> exceptionHandler = (Consumer<Throwable>) mock(Consumer.class);
    ResponseHandler responseHandler = new ResponseHandler(Optional.empty(), Optional.of(exceptionHandler));

    Exception testException = new Exception("oh no");
    responseHandler.exceptionCaught(null, testException);

    verify(exceptionHandler).accept(testException);
  }

  @Test
  @Ignore
  public void itDoesNotPasExceptionsToTheProvidedHandlerIfThereIsAPendingFuture() throws Exception {
    Consumer<Throwable> exceptionHandler = (Consumer<Throwable>) mock(Consumer.class);
    ResponseHandler responseHandler = new ResponseHandler(Optional.empty(), Optional.of(exceptionHandler));
    responseHandler.createResponseFuture(context.channel().attr(CHANNEL_KEY).get(),1, DEBUG_STRING);
    Exception testException = new Exception("oh no");
    responseHandler.exceptionCaught(null, testException);
    verify(exceptionHandler, never()).accept(testException);
  }

  @Test
  public void itPassesExceptionsToTheSuperclassIfTheHandlerIsNotProvided() throws Exception {
    ResponseHandler responseHandler = new ResponseHandler(Optional.empty(), Optional.empty());

    Exception testException = new Exception("oh no");
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

    responseHandler.exceptionCaught(ctx, testException);

    verify(ctx).fireExceptionCaught(testException);
  }
}
