import { Link } from 'react-router-dom';
import { DevCodes } from '../components/DevCodes';
import { Card, Copy, Notice } from '../components/ui';
import { useAuth } from '../state/auth';

export function DevHelperPage() {
  const a = useAuth();
  const email = a.account?.email ?? 'you@example.com';
  const cmd = `bash web/scripts/make-admin.sh ${email}`;
  return (
    <div className="stack">
      <h2>Dev helper (local only)</h2>
      <Notice kind="warn">
        This is a manual test console for the local stack. Tokens are kept in localStorage and request bodies appear in the debug panel: never point this at a shared or
        production environment.
      </Notice>

      <Card title="1. Verification codes and reset tokens">
        <p>
          Email and SMS are not really sent. The Notification service logs them with <code>DEV ONLY body:</code> in <code>logs/notification.log</code> (addresses are masked there).
          The in-app feed redacts codes on purpose, so it cannot show them. The Vite dev server exposes that one log file at <code>/__dev/codes</code> (dev server only, never in a
          build), and the table below reads it. The Account page and the password pages embed the same table.
        </p>
        <p>
          By hand: <code>grep -a "DEV ONLY" logs/notification.log | tail</code>
        </p>
      </Card>
      <DevCodes />

      <Card title="2. Becoming ADMIN (no public way)">
        <p>
          Nothing in the API grants ADMIN. <code>infra/scripts/smoke-test.sh</code> inserts a row into the identity database through the postgres container; this console
          ships the same trick as a script that does only that:
        </p>
        <pre className="cmd">
          {cmd} <Copy text={cmd} />
        </pre>
        <p>
          Run it from the repo root in Git Bash, then <strong>log out and log in again</strong> (roles are baked into the JWT; a token refresh also works). The script is for the local
          docker-compose postgres only. An admin cannot approve their own listings, so use a second account as host.
        </p>
      </Card>

      <Card title="3. Reaching COMPLETED (and reviews) quickly">
        <ol>
          <li>
            Host publishes a listing with the <strong>24/7</strong> availability preset (so the window always fits).
          </li>
          <li>
            Driver opens the listing and clicks <strong>Soonest slot (fast lane)</strong>: a 30-minute window starting on the next 15-minute boundary that is at least ~17 minutes away.
            Book and pay with <code>fake_ok</code> (a request-to-book listing also needs the host to approve).
          </li>
          <li>
            Booking allows <strong>check-in from 15 minutes before the start</strong>, so you wait at most about 15 minutes. The booking page shows a countdown and enables the button.
          </li>
          <li>
            Click <strong>Check in</strong> then <strong>Check out</strong>: check-out has no time gate, so the booking is COMPLETED at once (no need to wait for the end time).
          </li>
          <li>
            Booking emits BookingCompleted; Review learns about it over Kafka (a 404 for a few seconds is normal). Then both sides can review from the booking page.
          </li>
        </ol>
        <p className="muted">
          No config change or backdoor is needed. (If you do nothing, the completion timer, 30 s interval, completes a checked-in booking at its end time, and a missed check-in becomes
          NO_SHOW 30 minutes after the start.)
        </p>
      </Card>

      <Card title="4. Fake payment tokens (FakeProcessor)">
        <ul>
          <li>
            <code>fake_ok</code>: authorizes, capture succeeds.
          </li>
          <li>
            <code>fake_decline</code>: authorization fails with <code>card_declined</code>; the booking expires (book again).
          </li>
          <li>
            <code>fake_capture_fail</code>: authorizes, then capture is rejected when the booking is confirmed: booking becomes PAYMENT_FAILED.
          </li>
        </ul>
      </Card>

      <Card title="5. Where things are">
        <ul>
          <li>
            Gateway <code>:8080</code> (proxied here as <code>/api</code> and <code>/admin</code>), services <code>:8081-:8089</code>, logs in <code>logs/&lt;service&gt;.log</code>.
          </li>
          <li>
            Object storage console <code>http://localhost:9001</code>; presigned uploads go through the dev proxy at <code>/_storage</code> (no CORS on storage).
          </li>
          <li>
            Login/register are rate-limited at the gateway per IP. <code>npm run dev:direct-auth</code> sends <code>/api/v1/auth/*</code> straight to Identity :8081 like the smoke test does.
          </li>
          <li>
            Not in the backend: <Link to="/admin/not-implemented">suspend, disputes, payout holds</Link>.
          </li>
        </ul>
      </Card>

      {a.accessToken && (
        <Card title="Your current access token (for curl)">
          <p className="muted">
            <code>curl -H "Authorization: Bearer ..." http://localhost:8080/api/v1/accounts/me</code> <Copy text={a.accessToken} label="copy access token" />
          </p>
        </Card>
      )}
    </div>
  );
}

export function HomePage() {
  const a = useAuth();
  return (
    <div className="stack">
      <h2>ParkSmart test console</h2>
      <p>A browser front end for manually exercising every ParkSmart API flow through the gateway. Role-aware menu on top; the debug panel at the bottom shows the last request and response.</p>
      <div className="grid2">
        <Card title="Suggested walkthrough">
          <ol>
            <li>
              <Link to="/register">Register</Link> a host and a driver (use two browsers or log out between them).
            </li>
            <li>
              <Link to="/account">Account</Link>: verify email (codes in the dev helper), verify phone, become host.
            </li>
            <li>
              <Link to="/profile">Profile</Link>: display name and photo (hosts need both).
            </li>
            <li>
              <Link to="/host/listings/new">Create a listing</Link>, add a photo, set availability, submit.
            </li>
            <li>
              Make an admin (<Link to="/dev">Dev helper</Link>), approve it in <Link to="/admin/moderation">Moderation</Link>.
            </li>
            <li>
              As a driver: <Link to="/search">search</Link>, book, pay, check in/out, review.
            </li>
          </ol>
        </Card>
        <Card title="Session">
          {a.loggedIn ? (
            <p>
              Logged in as <strong>{a.account?.email ?? a.accountId}</strong> with roles {a.roles.join(', ')}.
            </p>
          ) : (
            <p>
              Not logged in. <Link to="/login">Log in</Link> or <Link to="/register">register</Link>.
            </p>
          )}
        </Card>
      </div>
    </div>
  );
}
