package com.parksmart.notification.domain;

import java.util.Objects;

/**
 * A rendered message (title + plain-text body). Values from users (reasons, titles) are cleaned:
 * control and invisible characters are removed and lengths capped, so they cannot break or spoof
 * the message. Bodies are plain text; an HTML email adapter must escape them.
 */
public record Message(String template, String title, String body) {

  public Message {
    Objects.requireNonNull(template, "template");
    title = clean(title, 200);
    body = clean(body, 4000);
  }

  /** Cleans untrusted text placed into a message. */
  public static String clean(String value, int max) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(Math.min(value.length(), max));
    value
        .codePoints()
        .filter(c -> c == '\n' || !(Character.isISOControl(c) || invisible(c)))
        .limit(max)
        .forEach(out::appendCodePoint);
    return out.toString();
  }

  private static boolean invisible(int c) {
    return (c >= 0x202A && c <= 0x202E)
        || (c >= 0x2066 && c <= 0x2069)
        || (c >= 0x200B && c <= 0x200F)
        || c == 0x2028
        || c == 0x2029
        || c == 0xFEFF;
  }
}
