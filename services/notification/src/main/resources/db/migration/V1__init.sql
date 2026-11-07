-- Notification Service: delivery of emails, SMS and in-app messages built from events.
-- Contact details arrive only on identity.private.v1 and are kept here (PII: never logged).

CREATE TABLE contacts (
  account_id UUID PRIMARY KEY,
  email VARCHAR(254),
  phone VARCHAR(20),
  changed_at TIMESTAMPTZ NOT NULL
);

-- Copy of Profile's preferences (NotificationPreferencesChanged). Missing row = all enabled.
CREATE TABLE channel_preferences (
  account_id UUID PRIMARY KEY,
  email BOOLEAN NOT NULL,
  sms BOOLEAN NOT NULL,
  push BOOLEAN NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL
);

-- Accounts with the ADMIN role (RoleGranted), for moderation notices.
CREATE TABLE admins (
  account_id UUID PRIMARY KEY,
  granted_at TIMESTAMPTZ NOT NULL
);

-- One row per message per channel. IN_APP rows are the user's feed (delivered when written);
-- EMAIL/SMS rows are sent by the dispatcher with retries.
CREATE TABLE notifications (
  id UUID PRIMARY KEY,
  account_id UUID,                       -- null only for codes sent to a raw destination
  channel VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'IN_APP')),
  template VARCHAR(60) NOT NULL,
  destination VARCHAR(254),              -- email address or phone for EMAIL/SMS
  title VARCHAR(200) NOT NULL,
  body TEXT NOT NULL,
  sensitive BOOLEAN NOT NULL DEFAULT false,   -- one-time codes: body redacted after sending
  status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  last_error VARCHAR(200),
  source_event_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  sent_at TIMESTAMPTZ,
  read_at TIMESTAMPTZ,
  CONSTRAINT uq_notification_per_event UNIQUE (source_event_id, channel, account_id, template)
);
CREATE INDEX idx_notifications_due ON notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_notifications_feed ON notifications (account_id, created_at DESC, id DESC)
  WHERE channel = 'IN_APP';
CREATE INDEX idx_notifications_unread ON notifications (account_id)
  WHERE channel = 'IN_APP' AND read_at IS NULL;

CREATE TABLE processed_events (
  event_id UUID NOT NULL,
  consumer VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_events_age ON processed_events (processed_at);
