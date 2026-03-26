package com.peekcart.notification.application.port;

/**
 * Slack 알림 발송 포트. Infrastructure 구현체(SlackNotificationClient)에 대한 의존 역전.
 */
public interface SlackPort {

    void send(String message);
}
