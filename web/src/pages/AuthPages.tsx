import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { useAuth } from '../state/auth';
import { Card, Field, Notice, ProblemView, useAction } from '../components/ui';
import { DevCodes } from '../components/DevCodes';

export function LoginPage() {
  const a = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const from = (loc.state as { from?: string } | null)?.from ?? '/account';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const act = useAction();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await act.run(async () => {
      await a.login(email, password);
      return true;
    });
    if (ok) nav(from);
  };
  return (
    <div className="narrow">
      <Card title="Log in">
        <form onSubmit={submit} className="form">
          <Field label="Email">
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="username" />
          </Field>
          <Field label="Password">
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="current-password" />
          </Field>
          <ProblemView error={act.error} />
          <button type="submit" disabled={act.busy}>
            Log in
          </button>
        </form>
        <p>
          <Link to="/forgot-password">Forgot password?</Link> | <Link to="/register">Create account</Link>
        </p>
        <Notice>
          The gateway rate-limits login to about 5 per IP, then 1 every 3 minutes. If you hit 429, restart the dev server with{' '}
          <code>npm run dev:direct-auth</code>.
        </Notice>
      </Card>
    </div>
  );
}

export function RegisterPage() {
  const a = useAuth();
  const nav = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [terms, setTerms] = useState('2024-01');
  const act = useAction();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await act.run(async () => {
      await auth.register(email, password, terms);
      await a.login(email, password);
      return true;
    });
    if (ok) nav('/account');
  };
  return (
    <div className="narrow">
      <Card title="Register">
        <form onSubmit={submit} className="form">
          <Field label="Email">
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={255} autoComplete="username" />
          </Field>
          <Field label="Password" hint="10-128 characters with at least one letter and one digit.">
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={10}
              maxLength={128}
              autoComplete="new-password"
            />
          </Field>
          <Field label="Accepted terms version">
            <input value={terms} onChange={(e) => setTerms(e.target.value)} required maxLength={20} />
          </Field>
          <ProblemView error={act.error} />
          <button type="submit" disabled={act.busy}>
            Register and log in
          </button>
        </form>
        <p className="muted">New accounts are DRIVER and PENDING_VERIFICATION. You can log in to verify your email on the next page.</p>
      </Card>
    </div>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const act = useAction();
  return (
    <div className="narrow">
      <Card title="Forgot password">
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await act.run(async () => {
              await auth.forgot(email);
              return true;
            });
            if (ok) setSent(true);
          }}
        >
          <Field label="Email">
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <ProblemView error={act.error} />
          <button type="submit" disabled={act.busy}>
            Send reset token
          </button>
        </form>
        {sent && (
          <Notice kind="ok">
            Always 202, whether or not the email exists. Locally the reset token is "emailed" to the Notification log: copy it from below, then{' '}
            <Link to="/reset-password">reset the password</Link>.
          </Notice>
        )}
      </Card>
      <DevCodes purpose="password reset" />
    </div>
  );
}

export function ResetPasswordPage() {
  const nav = useNavigate();
  const [token, setToken] = useState('');
  const [pw, setPw] = useState('');
  const [done, setDone] = useState(false);
  const act = useAction();
  return (
    <div className="narrow">
      <Card title="Reset password">
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await act.run(async () => {
              await auth.reset(token.trim(), pw);
              return true;
            });
            if (ok) setDone(true);
          }}
        >
          <Field label="Reset token (opaque string, not a 6-digit code)">
            <input value={token} onChange={(e) => setToken(e.target.value)} required minLength={20} />
          </Field>
          <Field label="New password" hint="10-128 characters, a letter and a digit.">
            <input type="password" value={pw} onChange={(e) => setPw(e.target.value)} required minLength={10} autoComplete="new-password" />
          </Field>
          <ProblemView error={act.error} />
          <button type="submit" disabled={act.busy}>
            Set new password
          </button>
        </form>
        {done && (
          <Notice kind="ok">
            Password changed; every refresh token was revoked. <button onClick={() => nav('/login')}>Go to login</button>
          </Notice>
        )}
      </Card>
      <DevCodes purpose="password reset" onPick={(c) => setToken(c.code)} />
    </div>
  );
}
