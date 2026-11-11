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
