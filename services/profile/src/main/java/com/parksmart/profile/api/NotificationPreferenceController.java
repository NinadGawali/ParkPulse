package com.parksmart.profile.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.web.ApiException;
import com.parksmart.profile.api.dto.Requests;
import com.parksmart.profile.api.dto.Responses;
import com.parksmart.profile.application.NotificationPreferenceService;
import com.parksmart.profile.domain.NotificationChannel;
import jakarta.validation.Valid;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification-preferences")
class NotificationPreferenceController {

  private final NotificationPreferenceService preferences;

  NotificationPreferenceController(NotificationPreferenceService preferences) {
    this.preferences = preferences;
  }

  @GetMapping
  Requests.Preferences get() {
    return Responses.preferences(preferences.get(CurrentAccount.require().accountId()));
  }

  @PutMapping
  Requests.Preferences update(@Valid @RequestBody Requests.Preferences body) {
    Map<NotificationChannel, Boolean> changes = new EnumMap<>(NotificationChannel.class);
    for (Requests.PreferenceEntry entry : body.preferences()) {
      if (changes.put(entry.channel(), entry.enabled()) != null) {
        throw ApiException.badRequest(
            "validation-failed", "Validation failed", "Channel listed twice: " + entry.channel());
      }
    }
    return Responses.preferences(preferences.update(CurrentAccount.require().accountId(), changes));
  }
}
