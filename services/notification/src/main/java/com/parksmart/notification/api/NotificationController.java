package com.parksmart.notification.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.web.CursorPage;
import com.parksmart.notification.application.FeedService;
import com.parksmart.notification.application.port.NotificationStore.Notification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's in-app feed (docs/openapi/notification.yaml). */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

  private final FeedService feed;

  NotificationController(FeedService feed) {
    this.feed = feed;
  }

  record Item(UUID id, String type, String title, String body, Instant createdAt, Instant readAt) {
    static Item of(Notification n) {
      return new Item(n.id(), n.template(), n.title(), n.body(), n.createdAt(), n.readAt());
    }
  }

  @GetMapping
  CursorPage<Item> list(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return feed.feed(callerId(), cursor, limit).map(Item::of);
  }

  @GetMapping("/unread-count")
  Map<String, Long> unread() {
    return Map.of("unread", feed.unread(callerId()));
  }

  @PostMapping("/{notificationId}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void read(@PathVariable UUID notificationId) {
    feed.markRead(callerId(), notificationId);
  }

  @PostMapping("/read-all")
  Map<String, Integer> readAll() {
    return Map.of("marked", feed.markAllRead(callerId()));
  }

  private static UUID callerId() {
    return CurrentAccount.require().accountId();
  }
}
