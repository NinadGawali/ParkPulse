import { useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { admin } from '../api/endpoints';
import { newIdempotencyKey } from '../api/client';
import { usePaged } from '../components/paged';
import { Badge, Card, Field, Json, LoadMore, Loading, Money, Notice, ProblemView, Time, useAction, useLoad } from '../components/ui';

export function ModerationPage() {
  const q = usePaged((c) => admin.queue(c));
  const act = useAction();
  const [reason, setReason] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  return (
    <div className="stack">
      <div className="row">
        <h2>Listing moderation queue</h2>
        <div className="grow" />
        <button onClick={q.reload}>Refresh</button>
      </div>
      <Notice>
        Oldest first, composed by the Admin BFF with each host's public profile. An admin cannot moderate their own listing (403). Reject needs a reason; the host sees it.
      </Notice>
      <Field label="Reason (required to reject, optional note for approve)">
        <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
      </Field>
      {msg && <Notice kind="ok">{msg}</Notice>}
      <ProblemView error={q.error} />
      <ProblemView error={act.error} />
      {q.loading && q.items.length === 0 && <Loading />}
      {!q.loading && q.items.length === 0 && !q.error && <p className="muted">Queue is empty.</p>}
      <div className="cards">
        {q.items.map((i) => (
          <div key={i.listingId} className="listing-card">
            {i.thumbnailUrl ? <img src={i.thumbnailUrl} alt="" /> : <div className="noimg">no photo</div>}
            <div>
              <strong>{i.title}</strong> <Badge value={i.status} />
              {i.partial && <span className="bad-text"> partial view</span>}
              <div>{i.address ?? '-'}</div>
              <div>
                <Money value={i.pricePerHour} currency={i.currency} />/h | photos {i.photoCount ?? '?'} | rules {i.availabilityRuleCount ?? '?'} | changed <Time value={i.lastChangedAt} />
              </div>
              <div className="muted">
                Host: {i.host?.displayName ?? '(no name)'} rating {i.host?.ratingAvg ?? '-'} ({i.host?.ratingCount ?? 0}) <code>{i.hostAccountId?.slice(0, 8)}</code>
              </div>
              <code className="muted">{i.listingId}</code>
              <div className="row">
                <button
                  disabled={act.busy}
                  onClick={async () => {
                    const r = await act.run(() => admin.approve(i.listingId, reason || undefined));
                    if (r) {
                      setMsg(`Approved ${i.title}: ${r.status}`);
                      q.reload();
                    }
                  }}
                >
                  Approve
                </button>
                <button
                  disabled={act.busy || !reason}
                  title={reason ? '' : 'Enter a reason first'}
                  onClick={async () => {
                    const r = await act.run(() => admin.reject(i.listingId, reason));
                    if (r) {
                      setMsg(`Rejected ${i.title}: ${r.status}`);
                      q.reload();
                    }
                  }}
                >
                  Reject
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>
      <LoadMore cursor={q.next} onMore={q.more} busy={q.loading} />
    </div>
  );
}

export function AdminBookingPage() {
  const [sp, setSp] = useSearchParams();
  const id = sp.get('id') ?? '';
  const [idIn, setIdIn] = useState(id);
  const c = useLoad(() => (id ? admin.bookingCase(id) : Promise.resolve(null)), [id]);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('Goodwill refund');
  const act = useAction();
  const lastKey = useRef<{ sig: string; key: string } | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  const refund = async () => {
    const sig = `${id}|${amount}|${reason}`;
    if (!lastKey.current || lastKey.current.sig !== sig) lastKey.current = { sig, key: newIdempotencyKey() };
    const key = lastKey.current.key;
    const r = await act.run(() => admin.refund(id, amount, reason, key));
    if (r) {
      setOutcome(`HTTP ${r.status}: intent ${r.data.status}, refunded ${r.data.refundedAmount} (Idempotency-Key ${key}; pressing again replays the stored result)`);
      c.reload();
    }
  };
  const d = c.data;
  return (
    <div className="stack">
      <h2>Booking case</h2>
      <Card>
        <form
          className="form inline"
          onSubmit={(e) => {
            e.preventDefault();
            setSp({ id: idIn.trim() });
          }}
        >
          <Field label="Booking id">
            <input value={idIn} onChange={(e) => setIdIn(e.target.value)} size={40} required />
          </Field>
          <button type="submit">Load case</button>
        </form>
      </Card>
      <ProblemView error={c.error} />
      {c.loading && id && !d && <Loading />}
      {d && (
        <>
          {d.partial && <Notice kind="warn">Partial view: a secondary lookup (payment, listing or profiles) failed, so some parts are null.</Notice>}
          <div className="grid2">
            <Card title="Booking">
              <p>
                <Badge value={d.booking.status} /> <Money value={d.booking.totalAmount} currency={d.booking.currency} /> {d.booking.cancellationPolicy}
              </p>
              <p>
                <Time value={d.booking.startTime} /> to <Time value={d.booking.endTime} />
              </p>
              {d.booking.refundAmount && (
                <p>
                  Refund recorded: <Money value={d.booking.refundAmount} currency={d.booking.currency} />
                </p>
              )}
            </Card>
            <Card title="Payment">
              {d.payment ? (
                <p>
                  <Badge value={d.payment.status} /> amount <Money value={d.payment.amount} currency={d.payment.currency} /> refunded{' '}
                  <Money value={d.payment.refundedAmount} currency={d.payment.currency} /> <code className="muted">{d.payment.intentId}</code>
                </p>
              ) : (
                <p className="muted">No intent yet (or lookup failed).</p>
              )}
            </Card>
            <Card title="Listing">{d.listing ? <p>{d.listing.title} <Badge value={d.listing.status} /> {d.listing.address}</p> : <p className="muted">-</p>}</Card>
            <Card title="People">
              <p>Driver: {d.driver?.displayName ?? '(no name)'} <code className="muted">{d.booking.driverAccountId.slice(0, 8)}</code></p>
              <p>Host: {d.host?.displayName ?? '(no name)'} <code className="muted">{d.booking.hostAccountId.slice(0, 8)}</code></p>
              <p className="muted">No contact data is returned by the BFF.</p>
            </Card>
          </div>
          <Card title="Manual refund (admin, Idempotency-Key)">
            <p className="muted">Only for CAPTURED or PARTIALLY_REFUNDED intents, up to the remaining captured amount (422 refund-exceeds-captured, 409 otherwise).</p>
            <form
              className="form inline"
              onSubmit={(e) => {
                e.preventDefault();
                void refund();
              }}
            >
              <Field label="Amount (INR)">
                <input value={amount} onChange={(e) => setAmount(e.target.value)} required pattern="\d{1,8}(\.\d{1,2})?" placeholder="10.00" />
              </Field>
              <Field label="Reason">
                <input value={reason} onChange={(e) => setReason(e.target.value)} required maxLength={255} />
              </Field>
              <button type="submit" disabled={act.busy}>
                Refund
              </button>
            </form>
            {outcome && <Notice kind="ok">{outcome}</Notice>}
            <ProblemView error={act.error} />
          </Card>
          <details>
            <summary>Raw case JSON</summary>
            <Json value={d} />
          </details>
        </>
      )}
    </div>
  );
}

export function AdminReviewsPage() {
  const [id, setId] = useState('');
  const [reason, setReason] = useState('Violates guidelines');
  const act = useAction();
  const [out, setOut] = useState<string | null>(null);
  return (
    <div className="stack">
      <h2>Hide a review</h2>
      <Notice>
        Find review ids on the <Link to="/reviews">Reviews</Link> page. Hiding is idempotent and recomputes the rating aggregate.
      </Notice>
      <Card>
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const r = await act.run(() => admin.hideReview(id.trim(), reason));
            if (r) setOut(`Hidden: review ${r.reviewId}`);
          }}
        >
          <Field label="Review id">
            <input value={id} onChange={(e) => setId(e.target.value)} required size={40} />
          </Field>
          <Field label="Reason">
            <input value={reason} onChange={(e) => setReason(e.target.value)} required maxLength={255} />
          </Field>
          <button type="submit" disabled={act.busy}>
            Hide review
          </button>
        </form>
        {out && <Notice kind="ok">{out}</Notice>}
        <ProblemView error={act.error} />
      </Card>
    </div>
  );
}

export function NotImplementedPage() {
  return (
    <div className="stack">
      <h2>Not implemented in the backend</h2>
      <Notice kind="warn">
        These admin capabilities have no endpoints yet (docs/PROGRESS.md, phase 10 gaps), so the console cannot offer them. They are not hidden failures.
      </Notice>
      <Card>
        <ul>
          <li>
            <strong>Account suspension / reinstatement</strong>: Identity has no admin suspend endpoint, and there is no AccountReinstated event.
          </li>
          <li>
            <strong>Disputes</strong>: no service owns a dispute table; manual refunds on the booking case are the only dispute tool.
          </li>
          <li>
            <strong>Payout holds and admin payout views</strong>: Payment has no hold endpoint; the Payout status ON_HOLD exists but nothing sets it.
          </li>
          <li>
            <strong>Public booking quote</strong>: the quote is an internal endpoint, never routed by the gateway.
          </li>
          <li>
            <strong>Listing photo reorder</strong>: implemented in the API (PUT /photos/order) but not in this console.
          </li>
        </ul>
      </Card>
    </div>
  );
}
