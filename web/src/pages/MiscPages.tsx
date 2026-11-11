import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { notifications, reviews } from '../api/endpoints';
import type { ReviewTarget } from '../api/types';
import { usePaged } from '../components/paged';
import { Card, Field, LoadMore, Loading, Notice, ProblemView, Time, useAction, useLoad } from '../components/ui';
import { useAuth } from '../state/auth';

export function NotificationsPage() {
  const p = usePaged((c) => notifications.list(c));
  const unread = useLoad(() => notifications.unread());
  const act = useAction();
  const refresh = () => {
    p.reload();
    unread.reload();
  };
  return (
    <div className="stack">
      <div className="row">
        <h2>Notifications</h2>
        <span className="count">{unread.data?.unread ?? 0} unread</span>
        <div className="grow" />
        <button onClick={refresh}>Refresh</button>
        <button
          disabled={act.busy}
          onClick={async () => {
            await act.run(() => notifications.readAll());
            refresh();
          }}
        >
          Mark all read
        </button>
      </div>
      <ProblemView error={p.error} />
      <ProblemView error={act.error} />
      {p.loading && p.items.length === 0 && <Loading />}
      {!p.loading && p.items.length === 0 && <p className="muted">No notifications yet. They arrive from booking, payment and listing events.</p>}
      <ul className="plain feed">
        {p.items.map((n) => (
          <li key={n.id} className={n.readAt ? 'read' : 'unread'}>
            <div className="row">
              <strong>{n.title}</strong> <code className="muted">{n.type}</code>
              <div className="grow" />
              <Time value={n.createdAt} />
              {!n.readAt && (
                <button
                  onClick={async () => {
                    await act.run(() => notifications.read(n.id));
                    refresh();
                  }}
                >
                  Mark read
                </button>
              )}
            </div>
            <div>{n.body}</div>
          </li>
        ))}
      </ul>
      <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
    </div>
  );
}

export function ReviewsPage() {
  const [sp, setSp] = useSearchParams();
  const a = useAuth();
  const type = (sp.get('targetType') as ReviewTarget) || 'LISTING';
  const id = sp.get('targetId') || '';
  const [typeIn, setTypeIn] = useState<ReviewTarget>(type);
  const [idIn, setIdIn] = useState(id);
  const authed = a.loggedIn;
  const summary = useLoad(() => (id ? reviews.summary(type, id, authed) : Promise.resolve(null)), [type, id, authed]);
  const list = usePaged((c) => (id ? reviews.list(type, id, c, authed) : Promise.resolve({ items: [], nextCursor: null })), [type, id, authed]);
  return (
    <div className="stack">
      <h2>Reviews</h2>
      <Notice>
        Listing reviews are public. Driver reviews (host to driver) need a login (401 otherwise). Your own driver id is shown on the Account page's raw JSON. Review IDs
        (for admin hide) are in the list below.
      </Notice>
      <Card>
        <form
          className="form inline"
          onSubmit={(e) => {
            e.preventDefault();
            setSp({ targetType: typeIn, targetId: idIn.trim() });
          }}
        >
          <Field label="Target type">
            <select value={typeIn} onChange={(e) => setTypeIn(e.target.value as ReviewTarget)}>
              <option>LISTING</option>
              <option>DRIVER</option>
            </select>
          </Field>
          <Field label="Target id (uuid)">
            <input value={idIn} onChange={(e) => setIdIn(e.target.value)} required size={40} />
          </Field>
          <button type="submit">Show</button>
          {a.accountId && (
            <button type="button" onClick={() => (setTypeIn('DRIVER'), setIdIn(a.accountId ?? ''))}>
              Me as driver
            </button>
          )}
        </form>
      </Card>
      <ProblemView error={summary.error} />
      {summary.data && (
        <p>
          Average <strong>{summary.data.avgRating ?? '-'}</strong> from {summary.data.reviewCount} published reviews.
        </p>
      )}
      <ProblemView error={list.error} />
      <ul className="plain">
        {list.items.map((r) => (
          <li key={r.id}>
            {'*'.repeat(r.rating)} {r.rating}/5 <span className="muted">{r.direction}</span> {r.comment}
            <div className="muted">
              <Time value={r.createdAt} /> | review <code>{r.id}</code> | booking <Link to={`/bookings/${r.bookingId}`}>{r.bookingId.slice(0, 8)}</Link>
            </div>
          </li>
        ))}
      </ul>
      <LoadMore cursor={list.next} onMore={list.more} busy={list.loading} />
    </div>
  );
}
