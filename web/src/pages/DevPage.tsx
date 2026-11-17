import { useAuth } from '../state/auth';
import { DevCodes } from '../components/DevCodes';
import { Card, Copy } from '../components/ui';
import { soonestSlot } from '../lib';

/** DEV SERVER ONLY (routed only when import.meta.env.DEV): shortcuts for exercising the local stack. */
export function DevPage() {
  const a = useAuth();
  const slot = soonestSlot(Date.now());
  const cmd = `bash web/scripts/make-admin.sh ${a.account?.email ?? 'you@example.com'}`;
  return (
    <div className="container page stack">
      <h1>Dev tools</h1>
      <p className="muted">Only on the Vite dev server. Never part of a production build.</p>
      <DevCodes />
      <div className="grid-2">
        <Card title="Become ADMIN (local only)">
          <p className="small">
            Run from the repo root in Git Bash, then log out and in again (roles live in the token):
          </p>
          <pre className="json">{cmd}</pre>
          <Copy text={cmd} label="Copy command" />
          <p className="small muted" style={{ marginTop: 8 }}>
            Admins cannot approve their own listings: use a second account as the host.
          </p>
        </Card>
        <Card title="Reach COMPLETED quickly">
          <ol className="small" style={{ paddingLeft: 18, margin: 0 }}>
            <li>Host publishes a listing with 24/7 opening hours.</li>
            <li>
              Driver books the soonest slot: arrive {slot.start.toLocaleTimeString()}, leave {slot.end.toLocaleTimeString()} (check-in opens {slot.checkInOpens.toLocaleTimeString()}).
            </li>
            <li>Pay with card 4242 4242 4242 4242.</li>
            <li>When check-in opens, check in, then check out straight away.</li>
            <li>Both sides can now leave a review (retry after a few seconds if it says the booking isn't known yet).</li>
          </ol>
        </Card>
      </div>
      <Card title="Session">
        <p className="small">
          Account <code>{a.accountId ?? '–'}</code> · roles {a.roles.join(', ') || 'none'} · email {a.account?.emailVerified ? 'verified' : 'unverified'} · phone{' '}
          {a.account?.phoneVerified ? 'verified' : 'unverified'}
        </p>
      </Card>
    </div>
  );
}
