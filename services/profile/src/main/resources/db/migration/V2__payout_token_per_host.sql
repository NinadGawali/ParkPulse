-- Security review: a global UNIQUE (processor_type, processor_token) let one host squat another
-- host's token and turned the 409 into a cross-account oracle. Tokens are now unique per host;
-- Payment Service must confirm with the processor that a token belongs to the host before
-- paying out (verified = true), so an unverified duplicate on another account is harmless.
ALTER TABLE payout_accounts DROP CONSTRAINT uq_payout_processor_token;
ALTER TABLE payout_accounts
  ADD CONSTRAINT uq_payout_token_per_host UNIQUE (host_account_id, processor_type, processor_token);
