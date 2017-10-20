package com.hubspot.smtp.messages;

import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import javax.activation.DataSource;
import javax.activation.FileTypeMap;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.Date;

public class MessageBuilder {
    private MimeMessageHelper helper;

    public MessageBuilder(JavaMailSender sender) {
        this.helper = new MimeMessageHelper(sender.createMimeMessage(), "UTF-8");
    }

    public MimeMessage getMimeMessage() {
        return helper.getMimeMessage();
    }

    public boolean isMultipart() {
        return helper.isMultipart();
    }

    public MimeMultipart getRootMimeMultipart() throws IllegalStateException {
        return helper.getRootMimeMultipart();
    }

    public MimeMultipart getMimeMultipart() throws IllegalStateException {
        return helper.getMimeMultipart();
    }

    public String getEncoding() {
        return helper.getEncoding();
    }

    public MessageBuilder setFileTypeMap(FileTypeMap fileTypeMap) {
        helper.setFileTypeMap(fileTypeMap);
        return this;
    }

    public FileTypeMap getFileTypeMap() {
        return helper.getFileTypeMap();
    }

    public MessageBuilder setValidateAddresses(boolean validateAddresses) {
        helper.setValidateAddresses(validateAddresses);
        return this;
    }

    public boolean isValidateAddresses() {
        return helper.isValidateAddresses();
    }

    public MessageBuilder setFrom(InternetAddress from) throws MessagingException {
        helper.setFrom(from);
        return this;
    }

    public MessageBuilder setFrom(String from) throws MessagingException {
        helper.setFrom(from);
        return this;
    }

    public MessageBuilder setFrom(String from, String personal) throws MessagingException, UnsupportedEncodingException {
        helper.setFrom(from, personal);
        return this;
    }

    public MessageBuilder setReplyTo(InternetAddress replyTo) throws MessagingException {
        helper.setReplyTo(replyTo);
        return this;
    }

    public MessageBuilder setReplyTo(String replyTo) throws MessagingException {
        helper.setReplyTo(replyTo);
        return this;
    }

    public MessageBuilder setReplyTo(String replyTo, String personal) throws MessagingException, UnsupportedEncodingException {
        helper.setReplyTo(replyTo, personal);
        return this;
    }

    public MessageBuilder setTo(InternetAddress to) throws MessagingException {
        helper.setTo(to);
        return this;
    }

    public MessageBuilder setTo(InternetAddress[] to) throws MessagingException {
        helper.setTo(to);
        return this;
    }

    public MessageBuilder setTo(String to) throws MessagingException {
        helper.setTo(to);
        return this;
    }

    public MessageBuilder setTo(String[] to) throws MessagingException {
        helper.setTo(to);
        return this;
    }

    public MessageBuilder addTo(InternetAddress to) throws MessagingException {
        helper.addTo(to);
        return this;
    }

    public MessageBuilder addTo(String to) throws MessagingException {
        helper.addTo(to);
        return this;
    }

    public MessageBuilder addTo(String to, String personal) throws MessagingException, UnsupportedEncodingException {
        helper.addTo(to, personal);
        return this;
    }

    public MessageBuilder setCc(InternetAddress cc) throws MessagingException {
        helper.setCc(cc);
        return this;
    }

    public MessageBuilder setCc(InternetAddress[] cc) throws MessagingException {
        helper.setCc(cc);
        return this;
    }

    public MessageBuilder setCc(String cc) throws MessagingException {
        helper.setCc(cc);
        return this;
    }

    public MessageBuilder setCc(String[] cc) throws MessagingException {
        helper.setCc(cc);
        return this;
    }

    public MessageBuilder addCc(InternetAddress cc) throws MessagingException {
        helper.addCc(cc);
        return this;
    }

    public MessageBuilder addCc(String cc) throws MessagingException {
        helper.addCc(cc);
        return this;
    }

    public MessageBuilder addCc(String cc, String personal) throws MessagingException, UnsupportedEncodingException {
        helper.addCc(cc, personal);
        return this;
    }

    public MessageBuilder setBcc(InternetAddress bcc) throws MessagingException {
        helper.setBcc(bcc);
        return this;
    }

    public MessageBuilder setBcc(InternetAddress[] bcc) throws MessagingException {
        helper.setBcc(bcc);
        return this;
    }

    public MessageBuilder setBcc(String bcc) throws MessagingException {
        helper.setBcc(bcc);
        return this;
    }

    public MessageBuilder setBcc(String[] bcc) throws MessagingException {
        helper.setBcc(bcc);
        return this;
    }

    public MessageBuilder addBcc(InternetAddress bcc) throws MessagingException {
        helper.addBcc(bcc);
        return this;
    }

    public MessageBuilder addBcc(String bcc) throws MessagingException {
        helper.addBcc(bcc);
        return this;
    }

    public MessageBuilder addBcc(String bcc, String personal) throws MessagingException, UnsupportedEncodingException {
        helper.addBcc(bcc, personal);
        return this;
    }

    public MessageBuilder setPriority(int priority) throws MessagingException {
        helper.setPriority(priority);
        return this;
    }

    public MessageBuilder setSentDate(Date sentDate) throws MessagingException {
        helper.setSentDate(sentDate);
        return this;
    }

    public MessageBuilder setSubject(String subject) throws MessagingException {
        helper.setSubject(subject);
        return this;
    }

    public MessageBuilder setText(String text) throws MessagingException {
        helper.setText(text);
        return this;
    }

    public MessageBuilder setText(String text, boolean html) throws MessagingException {
        helper.setText(text, html);
        return this;
    }

    public MessageBuilder setText(String plainText, String htmlText) throws MessagingException {
        helper.setText(plainText, htmlText);
        return this;
    }

    public MessageBuilder addInline(String contentId, DataSource dataSource) throws MessagingException {
        helper.addInline(contentId, dataSource);
        return this;
    }

    public MessageBuilder addInline(String contentId, File file) throws MessagingException {
        helper.addInline(contentId, file);
        return this;
    }

    public MessageBuilder addInline(String contentId, Resource resource) throws MessagingException {
        helper.addInline(contentId, resource);
        return this;
    }

    public MessageBuilder addInline(String contentId, InputStreamSource inputStreamSource, String contentType) throws MessagingException {
        helper.addInline(contentId, inputStreamSource, contentType);
        return this;
    }

    public MessageBuilder addAttachment(String attachmentFilename, DataSource dataSource) throws MessagingException {
        helper.addAttachment(attachmentFilename, dataSource);
        return this;
    }

    public MessageBuilder addAttachment(String attachmentFilename, File file) throws MessagingException {
        helper.addAttachment(attachmentFilename, file);
        return this;
    }

    public MessageBuilder addAttachment(String attachmentFilename, InputStreamSource inputStreamSource) throws MessagingException {
        helper.addAttachment(attachmentFilename, inputStreamSource);
        return this;
    }

    public MessageBuilder addAttachment(String attachmentFilename, InputStreamSource inputStreamSource, String contentType) throws MessagingException {
        helper.addAttachment(attachmentFilename, inputStreamSource, contentType);
        return this;
    }
}