import { useState } from 'react';
import { Link } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { useAuth } from '../state/auth';
import { Badge, Card, Field, Json, Notice, ProblemView, useAction } from '../components/ui';
import { DevCodes } from '../components/DevCodes';

export function AccountPage() {
  const a = useAuth();
  const acct = a.account;
  const [emailCode, setEmailCode] = useState('');
  const [phone, setPhone] = useState('+91');
  const [phoneCode, setPhoneCode] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const emailReq = useAction();
  const emailVer = useAction();
  const phoneReq = useAction();
  const phoneVer = useAction();
  const host = useAction();

  /** Verifying changes the account state and the claims; re-reading /accounts/me is enough here. */
  const done = async (text: string) => {
    setMsg(text);
    await a.reloadAccount();
  };

  return (
    <div className="stack">
      <h2>Account</h2>
      {!acct ? (
        <p className="muted">Loading account...</p>
      ) : (
        <Card title="Status">
          <p>
            <strong>{acct.email}</strong> <Badge value={acct.status} /> roles in token: <code>{a.roles.join(', ')}</code>
          </p>
          <p>
            Email verified: <Badge value={acct.emailVerified ? 'ACTIVE' : 'PENDING'} /> Phone verified:{' '}
            <Badge value={acct.phoneVerified ? 'ACTIVE' : 'PENDING'} /> {acct.phone ? <span className="muted">({acct.phone})</span> : null}
          </p>
          <details>
            <summary>Raw account</summary>
            <Json value={acct} />
          </details>
        </Card>
      )}
      {msg && <Notice kind="ok">{msg}</Notice>}

      <div className="grid2">
        <Card title="1. Verify email">
          {acct?.emailVerified ? (
            <p>Verified.</p>
          ) : (
            <>
              <button
                onClick={() => emailReq.run(async () => (await auth.requestEmailCode(), setMsg('Email code requested (202). See the dev helper below.')))}
                disabled={emailReq.busy}
              >
                Send email code
              </button>
              <ProblemView error={emailReq.error} />
              <form
                className="form"
                onSubmit={async (e) => {
                  e.preventDefault();
                  const ok = await emailVer.run(async () => (await auth.verifyEmail(emailCode.trim()), true));
                  if (ok) await done('Email verified (204). Account is ACTIVE.');
                }}
              >
                <Field label="6-digit code">
                  <input value={emailCode} onChange={(e) => setEmailCode(e.target.value)} pattern="\d{6}" maxLength={6} required />
                </Field>
                <button type="submit" disabled={emailVer.busy}>
                  Verify email
                </button>
              </form>
              <ProblemView error={emailVer.error} />
            </>
          )}
        </Card>

        <Card title="2. Verify phone (needed to publish listings)">
          {acct?.phoneVerified ? (
            <p>Verified: {acct.phone}</p>
          ) : (
            <>
              <form
                className="form"
                onSubmit={(e) => {
                  e.preventDefault();
                  void phoneReq.run(async () => (await auth.requestPhoneCode(phone.trim()), setMsg('SMS code requested (202). See the dev helper below.')));
                }}
              >
                <Field label="Phone (E.164)" hint="e.g. +919812345678. Changing a verified phone marks it unverified.">
                  <input value={phone} onChange={(e) => setPhone(e.target.value)} pattern="\+[1-9]\d{7,14}" required />
                </Field>
                <button type="submit" disabled={phoneReq.busy}>
                  Send SMS code
                </button>
              </form>
              <ProblemView error={phoneReq.error} />
              <form
                className="form"
                onSubmit={async (e) => {
                  e.preventDefault();
                  const ok = await phoneVer.run(async () => (await auth.verifyPhone(phoneCode.trim()), true));
                  if (ok) await done('Phone verified (204).');
                }}
              >
                <Field label="6-digit code">
                  <input value={phoneCode} onChange={(e) => setPhoneCode(e.target.value)} pattern="\d{6}" maxLength={6} required />
                </Field>
                <button type="submit" disabled={phoneVer.busy}>
                  Verify phone
                </button>
              </form>
              <ProblemView error={phoneVer.error} />
            </>
          )}
        </Card>
      </div>

      <DevCodes
        onPick={(c) => {
          if (c.purpose === 'email') setEmailCode(c.code);
          if (c.purpose === 'phone') setPhoneCode(c.code);
        }}
      />

      <Card title="3. Become a host">
        {a.hasRole('HOST') ? (
          <p>
            You have HOST. Next: <Link to="/profile">set a display name and photo</Link> (required to publish), then{' '}
            <Link to="/host/listings/new">create a listing</Link>.
          </p>
        ) : (
          <>
            <p>Needs an ACTIVE account (verified email). The new role only appears in a token after a refresh; this button does that for you.</p>
            <button
              disabled={host.busy}
              onClick={() =>
                host.run(async () => {
                  await auth.becomeHost();
                  await auth.refreshNow();
                  await a.reloadAccount();
                  setMsg('HOST granted and the refresh token was rotated, so the new JWT carries HOST.');
                })
              }
            >
              Become host
            </button>
            <ProblemView error={host.error} />
          </>
        )}
      </Card>
      <Notice>
        Host publishing checklist: HOST role, verified phone, profile photo, at least one confirmed listing photo and availability rule, then an admin
        approves. Admin: see <Link to="/dev">Dev helper</Link>.
      </Notice>
    </div>
  );
}
