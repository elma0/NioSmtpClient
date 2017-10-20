package com.hubspot.smtp;

import com.google.common.collect.Lists;
import com.hubspot.smtp.client.Extension;
import com.hubspot.smtp.client.SmtpSessionConfig;
import com.hubspot.smtp.client.SmtpSessionFactory;
import com.hubspot.smtp.client.SmtpSessionFactoryConfig;
import com.hubspot.smtp.messages.MessageBuilder;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.james.protocols.api.Encryption;
import org.apache.james.protocols.api.logger.Logger;
import org.apache.james.protocols.netty.NettyServer;
import org.apache.james.protocols.smtp.*;
import org.apache.james.protocols.smtp.hook.AuthHook;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.MailParametersHook;
import org.apache.james.protocols.smtp.hook.MessageHook;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.IOUtils;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AsyncClientTest {
    private static final long MAX_MESSAGE_SIZE = 1234000L;
    private static final String CORRECT_USERNAME = "smtp-user";
    private static final String CORRECT_PASSWORD = "correct horse battery staple";
    private static final String RETURN_PATH = "return-path@example.com";
    private static final String RECIPIENT = "sender@example.com";

    private static final NioEventLoopGroup EVENT_LOOP_GROUP = new NioEventLoopGroup();

    private InetSocketAddress serverAddress;
    private NettyServer smtpServer;
    private SmtpSessionFactory sessionFactory;
    private List<MailEnvelope> receivedMails;
    private String receivedMessageSize;
    private Logger serverLog;
    private boolean requireAuth;
    private CountDownLatch countDownLatch = new CountDownLatch(10);
    private AsyncEmailClient asyncEmailClient;

    private SmtpSessionConfig getDefaultConfig() {
        return SmtpSessionConfig.forRemoteAddress(serverAddress);
    }

    private SSLEngine createInsecureSSLEngine() {
        try {
            return SslContextBuilder
                    .forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build()
                    .newEngine(PooledByteBufAllocator.DEFAULT);
        } catch (Exception e) {
            throw new RuntimeException("Could not create SSLEngine", e);
        }
    }

    private synchronized static int getFreePort() {
        for (int port = 20000; port <= 30000; port++) {
            try {
                ServerSocket socket = new ServerSocket(port);
                socket.setReuseAddress(true);
                socket.close();
                return port;
            } catch (IOException ignored) {
                // ignore
            }
        }

        throw new RuntimeException("Could not find a port to listen on");
    }

    private class CollectEmailsHook implements MessageHook, MailParametersHook, AuthHook {
        @Override
        public synchronized HookResult onMessage(SMTPSession session, MailEnvelope mail) {
            receivedMails.add(mail);
            countDownLatch.countDown();
            return HookResult.ok();
        }

        @Override
        public HookResult doAuth(SMTPSession session, String username, String password) {
            if (username.equals(CORRECT_USERNAME) && password.equals(CORRECT_PASSWORD)) {
                return HookResult.ok();
            } else {
                return HookResult.deny();
            }
        }

        @Override
        public HookResult doMailParameter(SMTPSession session, String paramName, String paramValue) {
            if (paramName.equalsIgnoreCase("size")) {
                receivedMessageSize = paramValue;
            }

            return null;
        }

        @Override
        public String[] getMailParamNames() {
            return new String[]{"SIZE"};
        }
    }

    @Before
    public void setup() throws Exception {
        receivedMails = Lists.newArrayList();
        serverAddress = new InetSocketAddress(getFreePort());
        serverLog = mock(Logger.class);
        smtpServer = createAndStartSmtpServer(serverLog, serverAddress);
        sessionFactory = new SmtpSessionFactory(SmtpSessionFactoryConfig.nonProductionConfig().withSslEngineSupplier(this::createInsecureSSLEngine), getDefaultConfig().withDisabledExtensions(EnumSet.of(Extension.CHUNKING)));

        when(serverLog.isDebugEnabled()).thenReturn(true);

        asyncEmailClient = new AsyncEmailClient("hubspot.com",
                serverAddress.getHostName(),
                "test",
                "test",
                serverAddress.getPort(),
                10000);
    }

    private NettyServer createAndStartSmtpServer(Logger log, InetSocketAddress address) throws Exception {
        SMTPConfigurationImpl config = new SMTPConfigurationImpl() {
            @Override
            public boolean isAuthRequired(String remoteIP) {
                return requireAuth;
            }

            @Override
            public long getMaxMessageSize() {
                return MAX_MESSAGE_SIZE;
            }
        };

        SMTPProtocolHandlerChain chain = new SMTPProtocolHandlerChain(new CollectEmailsHook(), new ChunkingExtension());
        SMTPProtocol protocol = new SMTPProtocol(chain, config, log);
        Encryption encryption = Encryption.createStartTls(FakeTlsContext.createContext());

        NettyServer server = new ExtensibleNettyServer(protocol, encryption);
        server.setListenAddresses(address);
        server.bind();

        return server;
    }

    @After
    public void after() {
        smtpServer.unbind();
    }


    @Test
    public void testAsyncEmailClient() throws IOException, MessagingException, InterruptedException {
        MimeMessage mimeMessage = new MessageBuilder(asyncEmailClient)
                .addTo(RECIPIENT)
                .setFrom(RETURN_PATH)
                .setSubject("test mail")
                .setText("Hello world")
                .getMimeMessage();
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    asyncEmailClient.send(mimeMessage);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }
        countDownLatch.await(10, TimeUnit.SECONDS);
        assertThat(receivedMails.size()).isEqualTo(10);
        assertThat(new String(IOUtils.readFully(receivedMails.get(0).getMessageInputStream(), -1, true)).contains("Hello world")).isTrue();
    }
}
