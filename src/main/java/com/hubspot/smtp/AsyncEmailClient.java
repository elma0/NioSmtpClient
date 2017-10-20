package com.hubspot.smtp;


import com.hubspot.smtp.client.*;
import com.hubspot.smtp.utils.EventLoopGroupFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hubspot.smtp.messages.MessageContent.of;
import static io.netty.handler.codec.smtp.SmtpCommand.*;
import static java.lang.Integer.valueOf;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Stream.of;
import static javax.mail.Message.RecipientType.*;

public class AsyncEmailClient implements JavaMailSender {
    private String domain;
    private String localhost;
    private String username;
    private String password;
    private SmtpSessionFactory factory;
    private ThreadLocal<CompletableFuture<SmtpClientResponse>> threadLocal;
    private static final Logger LOG = LoggerFactory.getLogger(AsyncEmailClient.class);

    public AsyncEmailClient(String domain, String remoteAddress, String username, String password, int smtpPort, long connectionTimeout) throws UnknownHostException {
        this.domain = domain;
        this.username = username;
        this.password = password;
        InetAddress remote;
        localhost = InetAddress.getLocalHost().getCanonicalHostName();
        remote = InetAddress.getByName(remoteAddress);
        SmtpSessionConfig smtpSessionConfig = SmtpSessionConfig
                .builder()
                .connectionTimeout(ofSeconds(connectionTimeout))
                .remoteAddress(new InetSocketAddress(remote, valueOf(smtpPort)))
                .build();
        int processors = Runtime.getRuntime().availableProcessors();
        factory = new SmtpSessionFactory(
                SmtpSessionFactoryConfig
                        .builder()
                        .executor(newFixedThreadPool(processors))
                        .eventLoopGroup(EventLoopGroupFactory.create(processors))
                        .build(),
                smtpSessionConfig);
        threadLocal = ThreadLocal.withInitial(() -> factory.connect()
                .thenCompose(this::assertSuccess)
                .thenCompose(s -> s.send(req(EHLO, remoteAddress)))
                .thenCompose(r -> auth(r, domain, localhost, username, password)));
    }


    public void send(MimeMessage msg) throws MailException {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            msg.writeTo(stream);
            String from = toString(msg.getFrom());
            List<SmtpRequest> to = toRequest(RCPT, "TO:", msg.getRecipients(TO), msg.getRecipients(CC), msg.getRecipients(BCC));
            CompletableFuture<SmtpClientResponse> future = threadLocal.get().thenCompose(r -> ensureConnection(r, domain, localhost, username, password));
            future = future.thenCompose(this::assertSuccess).thenCompose(s -> s.send(req(MAIL, "FROM:<" + from + ">")));
            for (SmtpRequest req : to) {
                future = future.thenCompose(this::assertSuccess).thenCompose(s -> s.send(req));
            }
            future = future.thenCompose(this::assertSuccess).thenCompose(s -> s.send(req(DATA)))
                    .thenCompose(this::assertSuccess).thenCompose(s -> s.send(of(createBuffer(stream.toByteArray()))))
                    .thenCompose(this::assertSuccess).thenCompose(s -> s.send(req(RSET)))
                    .thenCompose(r -> {
                        close(stream);
                        return CompletableFuture.completedFuture(r);
                    })
                    .exceptionally(t -> {
                        close(stream);
                        return null;
                    });
            threadLocal.set(future);
        } catch (IOException | MessagingException e) {
            throw new MailPreparationException("Unable to parse email", e);
        }
    }

    private void close(OutputStream outputStream) {
        try {
            outputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private String toString(Address[]... addresses) {
        return of(addresses)
                .flatMap(Stream::of)
                .map(Address::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private List<SmtpRequest> toRequest(SmtpCommand command, String arg, Address[]... addresses) {
        return of(addresses)
                .filter(Objects::nonNull)
                .flatMap(Stream::of)
                .map(Address::toString)
                .map(to -> req(command, arg + "<" + to + ">"))
                .collect(Collectors.toCollection(ArrayList::new));
    }


    private static CompletableFuture<SmtpClientResponse> auth(SmtpClientResponse r, String domain, String localhost, String username, String password) {
        LOG.info("{} Auth: {}", Thread.currentThread().getName(), r);
        if (r.getSession().getEhloResponse().isAuthPlainSupported()) {
            return r.getSession().authPlain(username, password);
        } else if (r.getSession().getEhloResponse().isAuthLoginSupported()) {
            return r.getSession().authLogin(username, password);
        } else if (r.getSession().getEhloResponse().isAuthXoauth2Supported()) {
            return r.getSession().authXoauth2(username, password);
        } else if(r.getSession().getEhloResponse().isNtlmSupported()){
            return r.getSession().ntlmAuth(domain, localhost, username, password);
        } else {
            return CompletableFuture.completedFuture(r);
        }
    }

    private CompletableFuture<SmtpClientResponse> ensureConnection(SmtpClientResponse r, String domain, String localhost, String username, String password) {
        LOG.info("{} Start ensuring connection: {}", Thread.currentThread().getName(), r);
        if (!r.getSession().isActive()) {
            LOG.info("{} Connection closed. Reconnecting: {}", Thread.currentThread().getName(), r);
            return factory.connect()
                    .thenCompose(r1 -> r1.getSession().send(req(EHLO, domain)))
                    .thenCompose(r2 -> auth(r2, domain, localhost, username, password));
        } else {
            LOG.info("{} Connection active. Continue: {}", Thread.currentThread().getName(), r);
            return CompletableFuture.completedFuture(r);
        }
    }


    protected CompletableFuture<SmtpSession> assertSuccess(SmtpClientResponse r) {
        if (r.containsError()) {
            int code = r.getResponses().iterator().next().code();
            if (code != 535 && code != 550) {
                throw new MailSendException("Received error: " + r);
            } else {
                LOG.warn("Received error: {}", r);
            }
        }
        return CompletableFuture.completedFuture(r.getSession());
    }

    private ByteBuf createBuffer(byte[] s) {
        return Unpooled.wrappedBuffer(s);
    }

    private SmtpRequest req(SmtpCommand command, CharSequence... arguments) {
        return new DefaultSmtpRequest(command, arguments);
    }


    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Override
    public MimeMessage createMimeMessage(InputStream inputStream) throws MailException {
        try {
            return new MimeMessage(Session.getInstance(new Properties()), inputStream);
        } catch (MessagingException e) {
            throw new MailPreparationException("Unable to create mime message", e);
        }
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
        for (MimeMessage mimeMessage : mimeMessages) {
            send(mimeMessage);
        }
    }

    @Override
    public void send(MimeMessagePreparator mimeMessagePreparator) throws MailException {
        throw new NotImplementedException();
    }

    @Override
    public void send(MimeMessagePreparator... mimeMessagePreparators) throws MailException {
        throw new NotImplementedException();
    }


    @Override
    public void send(SimpleMailMessage simpleMailMessage) throws MailException {
        throw new NotImplementedException();
    }

    @Override
    public void send(SimpleMailMessage... simpleMailMessages) throws MailException {
        throw new NotImplementedException();
    }
}