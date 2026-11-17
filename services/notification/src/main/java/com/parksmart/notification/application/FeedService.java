package com.parksmart.notification.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.Cursor;
import com.parksmart.common.web.CursorPage;
import com.parksmart.notification.application.port.NotificationStore;
import com.parksmart.notification.application.port.NotificationStore.Notification;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's in-app feed. Marking read is owner-only (403 for anyone else). */
@Service
public class FeedService {

  private final NotificationStore store;
  private final Clock clock;

  public FeedService(NotificationStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CursorPage<Notification> feed(UUID accountId, String cursor, Integer limit) {
    Cursor after = Cursor.decode(cursor);
    int size = Cursor.limit(limit);
    List<Notification> rows =
        store.feed(
            accountId,
            after == null ? null : after.sortKey(),
            after == null ? null : after.id(),
            size + 1);
    if (rows.size() <= size) {
      return new CursorPage<>(rows, null);
    }
    List<Notification> items = rows.subList(0, size);
    Notification last = items.get(size - 1);
    return new CursorPage<>(items, new Cursor(last.createdAt(), last.id()).encode());
  }

  @Transactional(readOnly = true)
  public long unread(UUID accountId) {
    return store.unread(accountId);
  }

  @Transactional
  public void markRead(UUID callerId, UUID notificationId) {
    Notification n =
        store.findInApp(notificationId).orElseThrow(() -> ApiException.notFound("Notification"));
    if (!callerId.equals(n.accountId())) {
      throw ApiException.forbidden("Not your notification");
    }
    if (n.readAt() == null) {
      store.markRead(notificationId, clock.instant());
    }
  }

  @Transactional
  public int markAllRead(UUID callerId) {
    return store.markAllRead(callerId, clock.instant());
  }
}
