package com.peekcart.notification.application;

import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.domain.repository.NotificationRepository;
import com.peekcart.notification.infrastructure.slack.SlackNotificationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;
    private final SlackNotificationClient slackNotificationClient;

    public void createNotification(Long userId, NotificationType type, String message) {
        Notification notification = Notification.create(userId, type, message);
        notificationRepository.save(notification);
        slackNotificationClient.send(message);
    }
}
