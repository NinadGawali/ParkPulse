import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { auth } from '../api/endpoints';
import { useAuth } from '../state/auth';
import { Alert, Button, Field, ProblemView, isStatus, toast, useAction, useDocumentTitle, useNow } from '../components/ui';
import { DevCodes } from '../components/DevCodes';
import { Icon } from '../components/Icon';

const DEV = import.meta.env.DEV;
export const TERMS_VERSION = '2024-01';

function AuthShell({ title, sub, children, footer }: { title: string; sub?: ReactNode; children: ReactNode; footer?: ReactNode }) {
  useDocumentTitle(title);
  return (
    <div className="container xnarrow page">
      <div className="card card-pad stack">
        <div className="stack-xs center">
          <span className="brand-mark" style={{ margin: '0 auto 8px' }}>
            P
          </span>
          <h2>{title}</h2>
          {sub && <p className="muted">{sub}</p>}
        </div>
        {children}
      </div>
      {footer && <p className="center small muted" style={{ marginTop: 16 }}>{footer}</p>}
    </div>
  );
}

function PasswordInput({ id, value, onChange, autoComplete, minLength }: { id: string; value: string; onChange: (v: string) => void; autoComplete: string; minLength?: number }) {
  const [show, setShow] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <input id={id} type={show ? 'text' : 'password'} value={value} onChange={(e) => onChange(e.target.value)} required autoComplete={autoComplete} minLength={minLength} maxLength={128} style={{ paddingRight: 44 }} />
      <button
        type="button"
        className="btn btn-ghost btn-icon"
        style={{ position: 'absolute', right: 2, top: 2 }}
        onClick={() => setShow(!show)}
        aria-label={show ? 'Hide password' : 'Show password'}
      >
        <Icon name={show ? 'eyeOff' : 'eye'} size={17} />
      </button>
    </div>
  );
}

function fromState(loc: ReturnType<typeof useLocation>): string {
  const from = (loc.state as { from?: string } | null)?.from;
  // Only same-site paths (never an absolute URL handed in through state).
  return from && from.startsWith('/') && !from.startsWith('//') ? from : '/';
}

export function LoginPage() {
  const a = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const from = fromState(loc);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const act = useAction();
  if (a.ready && a.loggedIn) return <Navigate to={from} replace />;
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await act.run(async () => {
      await a.login(email.trim(), password);
      return true;
    });
    if (ok) nav(from, { replace: true });
  };
  return (
    <AuthShell
      title="Welcome back"
      sub="Log in to book and manage your parking."
      footer={
        <>
          New to ParkSmart?{' '}
          <Link to="/register" state={loc.state}>
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="form">
        <Field label="Email">
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="username" autoFocus />
        </Field>
        <Field label="Password" id="login-password">
          <PasswordInput id="login-password" value={password} onChange={setPassword} autoComplete="current-password" />
        </Field>
        <div className="row between">
          <span />
          <Link to="/forgot-password" className="small">
            Forgot password?
          </Link>
        </div>
        {isStatus(act.error, 401) ? <Alert kind="bad">That email and password don't match. Try again or reset your password.</Alert> : <ProblemView error={act.error} compact />}
        <Button type="submit" variant="primary" size="lg" block busy={act.busy}>
          Log in
        </Button>
      </form>
    </AuthShell>
  );
}

export function RegisterPage() {
  const a = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [agree, setAgree] = useState(false);
  const act = useAction();
  const pwOk = password.length >= 10 && /[A-Za-z]/.test(password) && /\d/.test(password);
  if (a.ready && a.loggedIn) return <Navigate to="/" replace />;
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!pwOk || !agree) return;
    const ok = await act.run(async () => {
      await auth.register(email.trim(), password, TERMS_VERSION);
      // Identity emails the first verification code itself when the account is created.
      await a.login(email.trim(), password);
      return true;
    });
    if (ok) nav('/verify-email', { replace: true, state: { from: fromState(loc), sent: true } });
  };
  return (
    <AuthShell
      title="Create your account"
      sub="Book parking in minutes, or earn from your own space."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" state={loc.state}>
            Log in
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="form">
        <Field label="Email">
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={255} autoComplete="username" autoFocus />
        </Field>
        <Field label="Password" id="new-password" hint={password && !pwOk ? undefined : 'At least 10 characters, with a letter and a number.'} error={password && !pwOk ? 'At least 10 characters, with a letter and a number.' : null}>
          <PasswordInput id="new-password" value={password} onChange={setPassword} autoComplete="new-password" minLength={10} />
        </Field>
        <label className="check small">
          <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} required />
          <span>
            I agree to the <Link to="/terms" target="_blank">Terms</Link> and <Link to="/privacy" target="_blank">Privacy policy</Link>.
          </span>
        </label>
        {isStatus(act.error, 409) ? (
          <Alert kind="bad">
            An account with this email already exists. <Link to="/login">Log in</Link> or <Link to="/forgot-password">reset your password</Link>.
          </Alert>
        ) : (
          <ProblemView error={act.error} compact />
        )}
        <Button type="submit" variant="primary" size="lg" block busy={act.busy} disabled={!agree || !pwOk}>
          Create account
        </Button>
      </form>
    </AuthShell>
  );
}

/** After sign-up (and from settings): confirm the 6-digit code we emailed. */
export function VerifyEmailPage() {
  const a = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const state = loc.state as { from?: string; sent?: boolean } | null;
  const from = fromState(loc);
  const [code, setCode] = useState('');
  const [sentAt, setSentAt] = useState<number | null>(state?.sent ? Date.now() : null);
  const now = useNow();
  const verify = useAction();
  const resend = useAction();
  useEffect(() => {
    if (a.account?.emailVerified) nav(from, { replace: true });
  }, [a.account?.emailVerified, from, nav]);
  if (a.ready && !a.loggedIn) return <Navigate to="/login" replace />;
  const wait = sentAt ? Math.max(0, 60 - Math.floor((now - sentAt) / 1000)) : 0;
  return (
    <AuthShell title="Check your email" sub={<>We sent a 6-digit code to <strong>{a.account?.email ?? 'your email'}</strong>.</>}>
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const ok = await verify.run(async () => {
            await auth.verifyEmail(code.trim());
            return true;
          });
          if (ok) {
            await a.reloadAccount();
            toast('Email verified');
            nav(from, { replace: true });
          }
        }}
      >
        <input className="otp" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" aria-label="Verification code" placeholder="••••••" autoFocus />
        <ProblemView error={verify.error} compact />
        <Button type="submit" variant="primary" size="lg" block busy={verify.busy} disabled={code.length !== 6}>
          Verify email
        </Button>
        <div className="row between small">
          <button
            type="button"
            className="link-btn"
            disabled={wait > 0 || resend.busy}
            onClick={async () => {
              const ok = await resend.run(async () => {
                await auth.requestEmailCode();
                return true;
              });
              if (ok) {
                setSentAt(Date.now());
                toast('New code sent');
              }
            }}
          >
            {wait > 0 ? `Resend code in ${wait}s` : 'Resend code'}
          </button>
          <Link to={from}>I'll do this later</Link>
        </div>
        <ProblemView error={resend.error} compact />
      </form>
      {DEV && <DevCodes purpose="email" onPick={(c) => setCode(c.code)} />}
    </AuthShell>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const act = useAction();
  return (
    <AuthShell title="Reset your password" sub="We'll email you a reset code." footer={<Link to="/login">Back to log in</Link>}>
      {sent ? (
        <Alert kind="ok" title="Check your inbox">
          If an account exists for {email}, a reset code is on its way. It expires after a while, so use it soon.
        </Alert>
      ) : (
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await act.run(async () => {
              await auth.forgot(email.trim());
              return true;
            });
            if (ok) setSent(true);
          }}
        >
          <Field label="Email">
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </Field>
          <ProblemView error={act.error} compact />
          <Button type="submit" variant="primary" size="lg" block busy={act.busy}>
            Send reset code
          </Button>
        </form>
      )}
      {sent && (
        <Link to="/reset-password" className="btn btn-primary btn-block">
          Enter the code
        </Link>
      )}
    </AuthShell>
  );
}

export function ResetPasswordPage() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const [token, setToken] = useState(sp.get('token') ?? '');
  const [pw, setPw] = useState('');
  const act = useAction();
  const pwOk = pw.length >= 10 && /[A-Za-z]/.test(pw) && /\d/.test(pw);
  return (
    <AuthShell title="Choose a new password" footer={<Link to="/login">Back to log in</Link>}>
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const ok = await act.run(async () => {
            await auth.reset(token.trim(), pw);
            return true;
          });
          if (ok) {
            toast('Password changed. Please log in.');
            nav('/login', { replace: true });
          }
        }}
      >
        {!sp.get('token') && (
          <Field label="Reset code from the email">
            <input value={token} onChange={(e) => setToken(e.target.value)} required minLength={20} />
          </Field>
        )}
        <Field label="New password" id="reset-password" error={pw && !pwOk ? 'At least 10 characters, with a letter and a number.' : null} hint="At least 10 characters, with a letter and a number.">
          <PasswordInput id="reset-password" value={pw} onChange={setPw} autoComplete="new-password" minLength={10} />
        </Field>
        <ProblemView error={act.error} compact />
        <Button type="submit" variant="primary" size="lg" block busy={act.busy} disabled={!pwOk || !token}>
          Set new password
        </Button>
        <p className="tiny muted">For your security, you'll be logged out on all devices.</p>
      </form>
      {DEV && !sp.get('token') && <DevCodes purpose="password reset" onPick={(c) => setToken(c.code)} />}
    </AuthShell>
  );
}
