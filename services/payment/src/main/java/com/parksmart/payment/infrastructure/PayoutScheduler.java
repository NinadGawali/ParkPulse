package com.parksmart.payment.infrastructure;

import com.parksmart.payment.application.PayoutService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Weekly payouts for the previous Monday-Sunday (UTC). */
@Component
@ConditionalOnProperty(value = "parksmart.payment.payouts.enabled", matchIfMissing = true)
class PayoutScheduler {

  private static final Logger log = LoggerFactory.getLogger(PayoutScheduler.class);

  private final PayoutService payouts;
  private final Clock clock;

  PayoutScheduler(PayoutService payouts, Clock clock) {
    this.payouts = payouts;
    this.clock = clock;
  }

  @Scheduled(cron = "${parksmart.payment.payouts.cron}", zone = "UTC")
  void weekly() {
    LocalDate lastSunday =
        LocalDate.now(clock.withZone(ZoneOffset.UTC))
            .with(TemporalAdjusters.previous(DayOfWeek.MONDAY))
            .minusDays(1);
    int paid = payouts.run(lastSunday);
    log.info("Payout run up to {}: {} payouts", lastSunday, paid);
  }
}
