import { describe, expect, it } from 'vitest';
import { estimateTotal, isQuarterAligned, refundTier, soonestSlot } from '../lib';
import { storageUrl } from '../api/endpoints';

describe('soonestSlot', () => {
  it('starts on a 15-minute boundary at least 15 minutes ahead and lasts 30 minutes', () => {
    for (const offset of [0, 1, 7, 14, 15, 29]) {
      const now = Date.UTC(2026, 10, 11, 10, offset, 33);
      const s = soonestSlot(now);
      expect(isQuarterAligned(s.start.toISOString())).toBe(true);
      expect(s.start.getTime() - now).toBeGreaterThanOrEqual(15 * 60_000);
      expect(s.start.getTime() - now).toBeLessThan(32 * 60_000);
      expect(s.end.getTime() - s.start.getTime()).toBe(30 * 60_000);
      expect(s.checkInOpens.getTime()).toBe(s.start.getTime() - 15 * 60_000);
    }
  });
});

describe('refundTier (common.yaml policy table)', () => {
  const start = '2026-11-20T10:00:00Z';
  const at = (h: number) => new Date(start).getTime() - h * 3_600_000;
  it('FLEXIBLE', () => {
    expect(refundTier('FLEXIBLE', start, at(1), '100.00').percent).toBe(100);
    expect(refundTier('FLEXIBLE', start, at(0.5), '100.00').percent).toBe(0);
  });
  it('MODERATE', () => {
    expect(refundTier('MODERATE', start, at(24), '100.00')).toMatchObject({ percent: 100, amount: '100.00' });
    expect(refundTier('MODERATE', start, at(5), '100.00')).toMatchObject({ percent: 50, amount: '50.00' });
    expect(refundTier('MODERATE', start, at(0.2), '100.00')).toMatchObject({ percent: 0, amount: '0.00' });
  });
  it('STRICT', () => {
    expect(refundTier('STRICT', start, at(48), '120.50').percent).toBe(50);
    expect(refundTier('STRICT', start, at(23), '120.50').percent).toBe(0);
  });
});

describe('helpers', () => {
  it('estimates totals', () => {
    expect(estimateTotal('50.00', '2026-11-20T10:00:00Z', '2026-11-20T12:30:00Z')).toBe('125.00');
  });
  it('rewrites presigned localhost:9000 URLs to the dev proxy, keeping the signature', () => {
    expect(storageUrl('http://localhost:9000/bucket/key?X-Amz-Signature=abc&X-Amz-Date=1')).toBe('/_storage/bucket/key?X-Amz-Signature=abc&X-Amz-Date=1');
    expect(storageUrl('https://cdn.example.com/x?y=1')).toBe('https://cdn.example.com/x?y=1');
  });
});

import { durationLabel, expiryValid, formatMoney, luhnValid, testCardToken, validateWindow } from '../lib';

describe('test-mode cards', () => {
  it('maps the documented test cards to FakeProcessor tokens and rejects others', () => {
    expect(testCardToken('4242 4242 4242 4242')).toBe('fake_ok');
    expect(testCardToken('4000000000000002')).toBe('fake_decline');
    expect(testCardToken('4000 0000 0000 0341')).toBe('fake_capture_fail');
    expect(testCardToken('5555 5555 5555 4444')).toBeNull();
  });
  it('checks Luhn and expiry', () => {
    expect(luhnValid('4242424242424242')).toBe(true);
    expect(luhnValid('4242424242424241')).toBe(false);
    const now = new Date(2026, 10, 16);
    expect(expiryValid('11/26', now)).toBe(true);
    expect(expiryValid('10/26', now)).toBe(false);
    expect(expiryValid('13/30', now)).toBe(false);
  });
});

describe('booking window validation (mirrors Booking)', () => {
  const now = Date.UTC(2026, 10, 16, 10, 0, 0);
  const at = (min: number) => new Date(now + min * 60_000).toISOString();
  it('accepts a valid window and explains each rule', () => {
    expect(validateWindow(at(30), at(120), now)).toBeNull();
    expect(validateWindow(at(0), at(120), now)).toMatch(/15 minutes/);
    expect(validateWindow(at(30), at(45), now)).toMatch(/30 minutes/);
    expect(validateWindow(at(31), at(120), now)).toMatch(/15-minute/);
    expect(validateWindow(at(30), at(30 + 31 * 1440), now)).toMatch(/30 days/);
  });
});

describe('formatting', () => {
  it('formats rupees and durations', () => {
    expect(formatMoney('1250.00')).toBe('₹1,250');
    expect(formatMoney('62.5')).toBe('₹62.50');
    expect(formatMoney(null)).toBe('–');
    expect(durationLabel('2026-11-20T10:00:00Z', '2026-11-20T12:30:00Z')).toBe('2 h 30 min');
    expect(durationLabel('2026-11-20T10:00:00Z', '2026-11-22T11:00:00Z')).toBe('2 days 1 h');
  });
});
