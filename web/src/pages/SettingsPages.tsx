import { useEffect, useState } from 'react';
import { Link, NavLink, Navigate, useNavigate, useParams } from 'react-router-dom';
import { auth, profile } from '../api/endpoints';
import { VEHICLE_TYPES, type Channel, type Vehicle, type VehicleType } from '../api/types';
import { VEHICLE_LABEL } from '../labels';
import { Icon, type IconName } from '../components/Icon';
import { PhotoUploader } from '../components/PhotoUploader';
import { DevCodes } from '../components/DevCodes';
import {
  Avatar,
  Badge,
  Button,
  Card,
  Empty,
  Field,
  Loading,
  Modal,
  ProblemView,
  Switch,
  toast,
  useAction,
  useDocumentTitle,
  useLoad,
  useNow,
} from '../components/ui';
import { forgetProfile } from '../state/cache';
import { useAuth } from '../state/auth';

const DEV = import.meta.env.DEV;
const TABS: { key: string; label: string; icon: IconName }[] = [
  { key: 'profile', label: 'Profile', icon: 'user' },
  { key: 'security', label: 'Login & verification', icon: 'shield' },
  { key: 'vehicles', label: 'Vehicles', icon: 'car' },
  { key: 'notifications', label: 'Notifications', icon: 'bell' },
];

export function SettingsPage() {
  const { tab = 'profile' } = useParams();
  const a = useAuth();
  useDocumentTitle('Account settings');
  if (!TABS.some((t) => t.key === tab)) return <Navigate to="/settings/profile" replace />;
  return (
    <div className="container page">
      <div className="page-head">
        <div>
          <h1>Account settings</h1>
          <p className="sub">{a.account?.email}</p>
        </div>
      </div>
      <div className="side-layout">
        <nav className="side-nav" aria-label="Settings">
          {TABS.map((t) => (
            <NavLink key={t.key} to={`/settings/${t.key}`} className={({ isActive }) => (isActive ? 'active' : '')}>
              <Icon name={t.icon} /> {t.label}
            </NavLink>
          ))}
          {a.hasRole('HOST') && (
            <>
              <span className="label">Hosting</span>
              <Link to="/host/earnings">
                <Icon name="wallet" /> Payouts
              </Link>
            </>
          )}
        </nav>
        <div className="stack">
          {tab === 'profile' && <ProfileTab />}
          {tab === 'security' && <SecurityTab />}
          {tab === 'vehicles' && <VehiclesTab />}
          {tab === 'notifications' && <NotificationsTab />}
        </div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ profile
function ProfileTab() {
  const a = useAuth();
  const p = useLoad(() => profile.me());
  const [name, setName] = useState('');
  const [bio, setBio] = useState('');
  const [city, setCity] = useState('');
  const save = useAction();
  const del = useAction();
  useEffect(() => {
    if (p.data) {
      setName(p.data.displayName ?? '');
      setBio(p.data.bio ?? '');
      setCity(p.data.city ?? '');
    }
  }, [p.data]);
  if (p.loading && !p.data) return <Loading />;
  if (!p.data) return <ProblemView error={p.error} />;
  const refreshed = () => {
    if (a.accountId) forgetProfile(a.accountId);
    p.reload();
  };
  return (
    <>
      <Card title="Profile photo">
        <div className="row top" style={{ gap: 20 }}>
          <Avatar name={p.data.displayName} url={p.data.photoUrl} size={96} />
          <div className="stack-sm grow" style={{ minWidth: 220 }}>
            <p className="muted small">A clear photo of your face helps hosts and drivers recognise you. Hosts need one to publish a listing.</p>
            <PhotoUploader
              label={p.data.hasPhoto ? 'Replace photo' : 'Upload a photo'}
              hint="JPEG, PNG or WebP up to 5 MB."
              maxBytes={5 * 1024 * 1024}
              createSlot={(ct, len) => profile.photoSlot(ct, len)}
              confirm={(slot) => profile.confirmPhoto(slot.uploadId as string)}
              onDone={() => {
                toast('Photo updated');
                refreshed();
              }}
            />
            {p.data.hasPhoto && (
              <button
                type="button"
                className="link-btn small"
                style={{ color: 'var(--bad)', alignSelf: 'flex-start' }}
                disabled={del.busy}
                onClick={async () => {
                  const ok = await del.run(async () => {
                    await profile.deletePhoto();
                    return true;
                  });
                  if (ok) refreshed();
                }}
              >
                Remove photo
              </button>
            )}
            <ProblemView error={del.error} compact />
          </div>
        </div>
      </Card>
      <Card title="About you">
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const r = await save.run(() => profile.update({ displayName: name.trim(), bio: bio.trim() || null, city: city.trim() || null }));
            if (r) {
              p.setData(r);
              if (a.accountId) forgetProfile(a.accountId);
              toast('Profile saved');
            }
          }}
        >
          <Field label="Display name" hint="Shown to hosts and drivers you book with.">
            <input value={name} onChange={(e) => setName(e.target.value)} required minLength={2} maxLength={60} autoComplete="name" />
          </Field>
          <Field label="City">
            <input value={city} onChange={(e) => setCity(e.target.value)} maxLength={100} autoComplete="address-level2" />
          </Field>
          <Field label="About">
            <textarea value={bio} onChange={(e) => setBio(e.target.value)} maxLength={500} rows={3} />
          </Field>
          <ProblemView error={save.error} compact />
          <div className="form-actions">
            <Button type="submit" variant="primary" busy={save.busy}>
              Save
            </Button>
          </div>
        </form>
        {p.data.ratingCount > 0 && (
          <p className="small muted" style={{ marginTop: 12 }}>
            Your driver rating: {Number(p.data.ratingAvg).toFixed(1)} ★ from {p.data.ratingCount} host review{p.data.ratingCount === 1 ? '' : 's'}.
          </p>
        )}
      </Card>
    </>
  );
}

// ------------------------------------------------------------------ security
function SecurityTab() {
  const a = useAuth();
  const nav = useNavigate();
  const acct = a.account;
  const [phoneOpen, setPhoneOpen] = useState(false);
  if (!acct) return <Loading />;
  return (
    <>
      <Card title="Email">
        <div className="row between">
          <span className="row">
            <Icon name="mail" />
            {acct.email}
          </span>
          {acct.emailVerified ? (
            <Badge tone="ok">Verified</Badge>
          ) : (
            <Button
              size="sm"
              variant="primary"
              onClick={async () => {
                await auth.requestEmailCode().catch(() => undefined);
                nav('/verify-email', { state: { from: '/settings/security', sent: true } });
              }}
            >
              Verify email
            </Button>
          )}
        </div>
      </Card>
      <Card title="Phone">
        <div className="row between">
          <span className="row">
            <Icon name="phone" />
            {acct.phone ?? <span className="muted">No phone number</span>}
          </span>
          <span className="row">
            {acct.phoneVerified && <Badge tone="ok">Verified</Badge>}
            <Button size="sm" variant={acct.phoneVerified ? 'secondary' : 'primary'} onClick={() => setPhoneOpen(true)}>
              {acct.phone ? (acct.phoneVerified ? 'Change' : 'Verify') : 'Add phone'}
            </Button>
          </span>
        </div>
        <p className="small muted" style={{ marginTop: 8 }}>
          Hosts need a verified phone number to publish listings. We use it for booking updates.
        </p>
      </Card>
      <Card title="Password">
        <div className="row between">
          <span className="muted">Reset your password with a code sent to your email.</span>
          <Link to="/forgot-password" className="btn btn-sm">
            Change password
          </Link>
        </div>
      </Card>
      {!a.hasRole('HOST') && (
        <Card title="Hosting">
          <div className="row between">
            <span className="muted">Have a parking space? Earn from it when you're not using it.</span>
            <Link to="/host" className="btn btn-sm">
              Become a host
            </Link>
          </div>
        </Card>
      )}
      {phoneOpen && <PhoneModal current={acct.phone ?? ''} onClose={() => setPhoneOpen(false)} />}
    </>
  );
}

function PhoneModal({ current, onClose }: { current: string; onClose: () => void }) {
  const a = useAuth();
  const [phone, setPhone] = useState(current || '+91');
  const [code, setCode] = useState('');
  const [sentAt, setSentAt] = useState<number | null>(null);
  const now = useNow();
  const send = useAction();
  const verify = useAction();
  const wait = sentAt ? Math.max(0, 60 - Math.floor((now - sentAt) / 1000)) : 0;
  const valid = /^\+[1-9]\d{7,14}$/.test(phone.trim());
  return (
    <Modal title="Verify your phone" onClose={onClose}>
      {sentAt === null ? (
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await send.run(async () => {
              await auth.requestPhoneCode(phone.trim());
              return true;
            });
            if (ok) setSentAt(Date.now());
          }}
        >
          <Field label="Mobile number" hint="Include the country code, e.g. +91 98123 45678.">
            <input value={phone} onChange={(e) => setPhone(e.target.value.replace(/[^\d+]/g, ''))} inputMode="tel" autoComplete="tel" autoFocus />
          </Field>
          <ProblemView error={send.error} compact />
          <Button type="submit" variant="primary" block busy={send.busy} disabled={!valid}>
            Send code by SMS
          </Button>
        </form>
      ) : (
        <form
          className="form"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await verify.run(async () => {
              await auth.verifyPhone(code.trim());
              return true;
            });
            if (ok) {
              await a.reloadAccount();
              toast('Phone verified');
              onClose();
            }
          }}
        >
          <p className="muted">Enter the 6-digit code we sent to {phone}.</p>
          <input className="otp" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" aria-label="SMS code" placeholder="••••••" autoFocus />
          <ProblemView error={verify.error} compact />
          <Button type="submit" variant="primary" block busy={verify.busy} disabled={code.length !== 6}>
            Verify
          </Button>
          <div className="row between small">
            <button type="button" className="link-btn" onClick={() => setSentAt(null)}>
              Change number
            </button>
            <button
              type="button"
              className="link-btn"
              disabled={wait > 0 || send.busy}
              onClick={async () => {
                const ok = await send.run(async () => {
                  await auth.requestPhoneCode(phone.trim());
                  return true;
                });
                if (ok) setSentAt(Date.now());
              }}
            >
              {wait > 0 ? `Resend in ${wait}s` : 'Resend code'}
            </button>
          </div>
          {DEV && <DevCodes purpose="phone" onPick={(c) => setCode(c.code)} />}
        </form>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ vehicles
function VehiclesTab() {
  const v = useLoad(() => profile.vehicles());
  const op = useAction();
  const [editing, setEditing] = useState<Vehicle | 'new' | null>(null);
  return (
    <Card
      title="Your vehicles"
      actions={
        <Button size="sm" icon="plus" onClick={() => setEditing('new')} disabled={(v.data?.items.length ?? 0) >= 10}>
          Add vehicle
        </Button>
      }
    >
      <ProblemView error={v.error} compact />
      {v.loading && !v.data ? (
        <Loading />
      ) : v.data?.items.length === 0 ? (
        <Empty icon="car" title="No vehicles yet" action={<Button variant="primary" onClick={() => setEditing('new')}>Add your vehicle</Button>}>
          Adding your vehicle lets hosts know what to expect and checks it fits the space.
        </Empty>
      ) : (
        <div className="stack">
          {v.data?.items.map((x) => (
            <div key={x.id} className="row between" style={{ padding: '6px 0', borderBottom: '1px solid var(--line)' }}>
              <span className="row">
                <Icon name="car" size={22} />
                <span>
                  <span className="strong">{x.licensePlate}</span> <span className="muted small">{x.plateRegion}</span> {x.primary && <Badge tone="brand">Default</Badge>}
                  <br />
                  <span className="small muted">{[VEHICLE_LABEL[x.vehicleType], x.make, x.model, x.color].filter(Boolean).join(' · ')}</span>
                </span>
              </span>
              <span className="row">
                {!x.primary && (
                  <Button
                    size="sm"
                    variant="ghost"
                    busy={op.busy}
                    onClick={async () => {
                      await op.run(() => profile.makePrimary(x.id));
                      v.reload();
                    }}
                  >
                    Make default
                  </Button>
                )}
                <Button size="sm" variant="ghost" icon="edit" onClick={() => setEditing(x)} aria-label="Edit vehicle" />
                <Button
                  size="sm"
                  variant="ghost"
                  icon="trash"
                  aria-label="Remove vehicle"
                  busy={op.busy}
                  onClick={async () => {
                    if (!confirm(`Remove ${x.licensePlate}?`)) return;
                    await op.run(() => profile.removeVehicle(x.id));
                    v.reload();
                  }}
                />
              </span>
            </div>
          ))}
        </div>
      )}
      <ProblemView error={op.error} compact />
      {editing && (
        <VehicleModal
          vehicle={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            v.reload();
          }}
        />
      )}
    </Card>
  );
}

function VehicleModal({ vehicle, onClose, onSaved }: { vehicle: Vehicle | null; onClose: () => void; onSaved: () => void }) {
  const [plate, setPlate] = useState(vehicle?.licensePlate ?? '');
  const [region, setRegion] = useState(vehicle?.plateRegion ?? 'IN-KA');
  const [type, setType] = useState<VehicleType>(vehicle?.vehicleType ?? 'HATCHBACK');
  const [make, setMake] = useState(vehicle?.make ?? '');
  const [model, setModel] = useState(vehicle?.model ?? '');
  const [color, setColor] = useState(vehicle?.color ?? '');
  const act = useAction();
  return (
    <Modal title={vehicle ? 'Edit vehicle' : 'Add a vehicle'} onClose={onClose}>
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const extra = { make: make.trim() || null, model: model.trim() || null, color: color.trim() || null };
          const r = await act.run(() =>
            vehicle ? profile.updateVehicle(vehicle.id, { vehicleType: type, ...extra }) : profile.addVehicle({ licensePlate: plate.trim(), plateRegion: region, vehicleType: type, ...extra }),
          );
          if (r) {
            toast(vehicle ? 'Vehicle updated' : 'Vehicle added');
            onSaved();
          }
        }}
      >
        <div className="grid-2">
          <Field label="Number plate" hint={vehicle ? "Plates can't be changed; remove and re-add instead." : undefined}>
            <input value={plate} onChange={(e) => setPlate(e.target.value.toUpperCase())} required minLength={2} maxLength={20} pattern="[A-Za-z0-9 \-]+" disabled={!!vehicle} placeholder="KA 01 AB 1234" />
          </Field>
          <Field label="Registered in" hint="Country and state code, e.g. IN-KA.">
            <input value={region} onChange={(e) => setRegion(e.target.value.toUpperCase())} required pattern="[A-Z]{2}(-[A-Z0-9]{1,3})?" disabled={!!vehicle} />
          </Field>
        </div>
        <Field label="Type">
          <select value={type} onChange={(e) => setType(e.target.value as VehicleType)}>
            {VEHICLE_TYPES.map((t) => (
              <option key={t} value={t}>
                {VEHICLE_LABEL[t]}
              </option>
            ))}
          </select>
        </Field>
        <div className="grid-3">
          <Field label="Make">
            <input value={make} onChange={(e) => setMake(e.target.value)} maxLength={60} placeholder="Maruti" />
          </Field>
          <Field label="Model">
            <input value={model} onChange={(e) => setModel(e.target.value)} maxLength={60} placeholder="Swift" />
          </Field>
          <Field label="Colour">
            <input value={color} onChange={(e) => setColor(e.target.value)} maxLength={30} placeholder="White" />
          </Field>
        </div>
        <ProblemView error={act.error} compact />
        <div className="form-actions">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" busy={act.busy}>
            {vehicle ? 'Save' : 'Add vehicle'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ notifications
const CHANNELS: { channel: Channel; label: string; text: string; locked?: boolean }[] = [
  { channel: 'EMAIL', label: 'Email', text: 'Booking confirmations, receipts and changes.' },
  { channel: 'SMS', label: 'Text messages', text: 'Time-sensitive updates, like a host cancelling.' },
  { channel: 'PUSH', label: 'Push notifications', text: 'On your phone, when the app is installed.' },
  { channel: 'IN_APP', label: 'In-app', text: 'The bell in the top bar. Always on.', locked: true },
];
function NotificationsTab() {
  const prefs = useLoad(() => profile.prefs());
  const act = useAction();
  const [state, setState] = useState<Record<string, boolean>>({});
  useEffect(() => {
    if (prefs.data) setState(Object.fromEntries(prefs.data.preferences.map((x) => [x.channel, x.enabled])));
  }, [prefs.data]);
  const save = async (next: Record<string, boolean>) => {
    setState(next);
    const r = await act.run(() => profile.setPrefs({ preferences: CHANNELS.map((c) => ({ channel: c.channel, enabled: c.locked ? true : (next[c.channel] ?? true) })) }));
    if (r) {
      prefs.setData(r);
      toast('Preferences saved');
    } else if (prefs.data) {
      setState(Object.fromEntries(prefs.data.preferences.map((x) => [x.channel, x.enabled])));
    }
  };
  return (
    <Card title="How we contact you">
      <ProblemView error={prefs.error} compact />
      {prefs.loading && !prefs.data ? (
        <Loading />
      ) : (
        <div>
          {CHANNELS.map((c) => (
            <div key={c.channel} className="toggle-row">
              <div>
                <div className="strong">{c.label}</div>
                <div className="small muted">{c.text}</div>
              </div>
              <Switch checked={c.locked ? true : (state[c.channel] ?? true)} disabled={c.locked || act.busy} onChange={(v) => void save({ ...state, [c.channel]: v })} label={c.label} />
            </div>
          ))}
        </div>
      )}
      <ProblemView error={act.error} compact />
    </Card>
  );
}
