package com.parksmart.profile.domain;

/** Payment processor that issued a payout account token. FAKE is for local and test only. */
public enum ProcessorType {
  STRIPE_CONNECT,
  RAZORPAY_ROUTE,
  FAKE
}
