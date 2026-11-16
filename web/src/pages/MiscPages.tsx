import { Link } from 'react-router-dom';
import { notifications } from '../api/endpoints';
import { POLICY_META } from '../labels';
import { relativeTime } from '../lib';
import { usePaged } from '../components/paged';
import { Button, Empty, Loading, LoadMore, ProblemView, useAction, useDocumentTitle } from '../components/ui';

export function NotificationsPage() {
  useDocumentTitle('Notifications');
  const p = usePaged((c) => notifications.list(c));
  const act = useAction();
  const unread = p.items.filter((n) => !n.readAt).length;
  return (
    <div className="container narrow page">
      <div className="page-head">
        <div>
          <h1>Notifications</h1>
          <p className="sub">Updates about your bookings, listings and payments.</p>
        </div>
        <div className="grow" />
        {unread > 0 && (
          <Button
            size="sm"
            busy={act.busy}
            onClick={async () => {
              await act.run(() => notifications.readAll());
              p.reload();
            }}
          >
            Mark all as read
          </Button>
        )}
      </div>
      <ProblemView error={p.error} />
      <ProblemView error={act.error} compact />
      {p.loading && p.items.length === 0 ? (
        <Loading />
      ) : p.items.length === 0 ? (
        <Empty icon="bell" title="No notifications yet">
          We'll let you know when something happens with your bookings or listings.
        </Empty>
      ) : (
        <div className="card">
          {p.items.map((n, i) => (
            <button
              key={n.id}
              type="button"
              className={`notif-item ${n.readAt ? '' : 'unread'}`}
              style={{ width: '100%', textAlign: 'left', border: 0, borderTop: i ? '1px solid var(--line)' : 0, borderRadius: 0, cursor: n.readAt ? 'default' : 'pointer', font: 'inherit', color: 'inherit', padding: '14px 18px' }}
              onClick={async () => {
                if (n.readAt) return;
                await act.run(() => notifications.read(n.id));
                p.reload();
              }}
            >
              <span className="row between">
                <strong>{n.title}</strong>
                <span className="tiny muted">{relativeTime(n.createdAt, Date.now())}</span>
              </span>
              <span className="small">{n.body}</span>
            </button>
          ))}
        </div>
      )}
      <div style={{ marginTop: 16 }}>
        <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
      </div>
    </div>
  );
}

function Doc({ title, children }: { title: string; children: React.ReactNode }) {
  useDocumentTitle(title);
  return (
    <div className="container narrow page">
      <h1 style={{ marginBottom: 20 }}>{title}</h1>
      <div className="stack-lg">{children}</div>
    </div>
  );
}

const FAQ: { q: string; a: React.ReactNode; id?: string }[] = [
  {
    q: 'How does booking work?',
    a: 'Search for an area and your arrival and departure times, pick a space and pay. Instant-book spaces are confirmed as soon as the payment goes through. Other hosts accept or decline within 24 hours (or before you arrive, if that is sooner); you are only charged once they accept.',
  },
  {
    q: 'When do I have to pay?',
    a: 'Right after you book. We hold the space for 10 minutes while you pay; unpaid bookings are released automatically.',
  },
  {
    q: 'How do check-in and check-out work?',
    a: 'Open your booking when you arrive. Check-in opens 15 minutes before your arrival time and stays open for 30 minutes after it; if you have not checked in by then the booking is marked as a no-show. Check out when you leave, or we close the booking at your departure time. Leaving early does not refund unused time.',
  },
  {
    id: 'cancellation',
    q: 'What if I need to cancel?',
    a: (
      <>
        Each listing has one of three policies, shown before you book:
        <ul>
          {Object.values(POLICY_META).map((p) => (
            <li key={p.label}>
              <strong>{p.label}</strong>: {p.long}
            </li>
          ))}
        </ul>
        Requests the host hasn't accepted yet can be cancelled for free. If a host cancels or declines, you get everything back.
      </>
    ),
  },
  {
    id: 'hosting',
    q: 'How does hosting work?',
    a: (
      <>
        Verify your email and become a host, add your phone number and a profile photo, then list your space with photos and opening hours. Our team reviews each listing
        before it goes live. You choose instant booking or approving each request, and can block dates or pause at any time. Payouts run weekly; ParkSmart keeps a 15% service
        fee. <Link to="/host">Start hosting</Link>.
      </>
    ),
  },
  {
    q: 'Is my payment information safe?',
    a: "Card details go to our payment provider and never touch ParkSmart's servers; we only store a reference to the payment. Hosts' payout details are held by the provider the same way.",
  },
];

export function HelpPage() {
  return (
    <Doc title="Help centre">
      {FAQ.map((f) => (
        <section key={f.q} id={f.id} className="stack-sm">
          <h3>{f.q}</h3>
          <div className="muted">{f.a}</div>
        </section>
      ))}
    </Doc>
  );
}

export function TermsPage() {
  return (
    <Doc title="Terms of service">
      <p className="muted">Version 2024-01. A summary of the rules everyone agrees to when using ParkSmart.</p>
      <section className="stack-sm">
        <h3>Using ParkSmart</h3>
        <p>ParkSmart connects drivers with people who have a parking space to rent. Hosts are responsible for their space being available, safe and as described. Drivers are responsible for their vehicle and for leaving on time.</p>
      </section>
      <section className="stack-sm">
        <h3>Bookings and payments</h3>
        <p>Prices are per hour in Indian rupees and shown in full before you book. Payment is taken through our payment provider. Cancellations and refunds follow the policy shown on each listing.</p>
      </section>
      <section className="stack-sm">
        <h3>Hosting</h3>
        <p>Listings are reviewed before they go live and can be paused or removed if they break these terms. ParkSmart keeps a service fee from each booking and pays out the rest weekly.</p>
      </section>
      <section className="stack-sm">
        <h3>Reviews</h3>
        <p>Reviews must be honest and about the booking. We may hide reviews that contain personal information, abuse or unrelated content.</p>
      </section>
    </Doc>
  );
}

export function PrivacyPage() {
  return (
    <Doc title="Privacy policy">
      <section className="stack-sm">
        <h3>What we collect</h3>
        <p>Your email and phone number (to sign you in and send booking updates), your profile name and photo, vehicles you add, and your bookings. Payment cards and bank details are held by our payment provider, not by ParkSmart.</p>
      </section>
      <section className="stack-sm">
        <h3>Who sees what</h3>
        <p>Hosts see your display name, photo and rating for bookings at their space. Listing addresses and host names are public. Your email and phone number are never shown to other users.</p>
      </section>
      <section className="stack-sm">
        <h3>Your choices</h3>
        <p>
          You can change your profile, vehicles and notification preferences in <Link to="/settings">account settings</Link> at any time.
        </p>
      </section>
    </Doc>
  );
}

export function NotFoundPage() {
  useDocumentTitle('Page not found');
  return (
    <div className="container narrow page">
      <Empty icon="map" title="We couldn't find that page" action={<Link to="/" className="btn btn-primary">Go home</Link>}>
        The link may be broken or the page may have moved.
      </Empty>
    </div>
  );
}
