import { useEffect, useState } from 'react';
import { profile } from '../api/endpoints';
import { useAuth } from '../state/auth';
import { VEHICLE_TYPES, type Channel, type VehicleType } from '../api/types';
import { Badge, Card, Field, Loading, Notice, ProblemView, useAction, useLoad } from '../components/ui';
import { PhotoUploader } from '../components/PhotoUploader';

export function ProfilePage() {
  const p = useLoad(() => profile.me());
  const [name, setName] = useState('');
  const [bio, setBio] = useState('');
  const [city, setCity] = useState('');
  const save = useAction();
  const del = useAction();
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (p.data) {
      setName(p.data.displayName ?? '');
      setBio(p.data.bio ?? '');
      setCity(p.data.city ?? '');
    }
  }, [p.data]);

  return (
    <div className="stack">
      <h2>Profile</h2>
      <ProblemView error={p.error} />
      {p.loading && !p.data && <Loading />}
      {p.data && (
        <div className="grid2">
          <Card title="Details">
            <form
              className="form"
              onSubmit={async (e) => {
                e.preventDefault();
                setSaved(false);
                const r = await save.run(() => profile.update({ displayName: name, bio: bio || null, city: city || null }));
                if (r) {
                  p.setData(r);
                  setSaved(true);
                }
              }}
            >
              <Field label="Display name" hint="2-60 characters. Required before you can publish a listing.">
                <input value={name} onChange={(e) => setName(e.target.value)} required minLength={2} maxLength={60} />
              </Field>
              <Field label="Bio">
                <textarea value={bio} onChange={(e) => setBio(e.target.value)} maxLength={500} rows={3} />
              </Field>
              <Field label="City">
                <input value={city} onChange={(e) => setCity(e.target.value)} maxLength={100} />
              </Field>
              <ProblemView error={save.error} />
              <button type="submit" disabled={save.busy}>
                Save
              </button>
              {saved && <span className="ok-text"> Saved.</span>}
            </form>
            <p className="muted">
              Driver rating: {p.data.ratingAvg ?? 'none yet'} ({p.data.ratingCount} reviews)
            </p>
          </Card>
          <Card title="Photo">
            {p.data.photoUrl ? <img className="avatar" src={p.data.photoUrl} alt="Profile" /> : <p className="muted">No photo. Hosts need one to publish.</p>}
            <PhotoUploader
              label="Upload a profile photo (jpeg/png/webp, max 5 MB)"
              maxBytes={5 * 1024 * 1024}
              createSlot={(ct, len) => profile.photoSlot(ct, len)}
              confirm={(slot) => profile.confirmPhoto(slot.uploadId as string)}
              onDone={p.reload}
            />
            {p.data.hasPhoto && (
              <>
                <button
                  disabled={del.busy}
                  onClick={async () => {
                    await del.run(() => profile.deletePhoto());
                    p.reload();
                  }}
                >
                  Remove photo
                </button>
                <ProblemView error={del.error} />
              </>
            )}
          </Card>
        </div>
      )}
      <Preferences />
    </div>
  );
}

const CHANNELS: Channel[] = ['EMAIL', 'SMS', 'PUSH', 'IN_APP'];
function Preferences() {
  const prefs = useLoad(() => profile.prefs());
  const act = useAction();
  const [state, setState] = useState<Record<string, boolean>>({});
  useEffect(() => {
    if (prefs.data) setState(Object.fromEntries(prefs.data.preferences.map((x) => [x.channel, x.enabled])));
  }, [prefs.data]);
  return (
    <Card title="Notification preferences">
      <ProblemView error={prefs.error} />
      <div className="row">
        {CHANNELS.map((c) => (
          <label key={c} className="check">
            <input
              type="checkbox"
              checked={state[c] ?? true}
              onChange={(e) => setState({ ...state, [c]: e.target.checked })}
            />{' '}
            {c}
          </label>
        ))}
        <button
          disabled={act.busy}
          onClick={async () => {
            const r = await act.run(() =>
              profile.setPrefs({ preferences: CHANNELS.map((channel) => ({ channel, enabled: state[channel] ?? true })) }),
            );
            if (r) prefs.setData(r);
          }}
        >
          Save
        </button>
      </div>
      <ProblemView error={act.error} />
      <p className="muted">IN_APP cannot be disabled (the API answers 422 channel-required: try unticking it to see the problem display).</p>
    </Card>
  );
}

// ------------------------------------------------------------------ vehicles
export function VehiclesPage() {
  const v = useLoad(() => profile.vehicles());
  const add = useAction();
  const op = useAction();
  const [plate, setPlate] = useState('');
  const [region, setRegion] = useState('IN-KA');
  const [type, setType] = useState<VehicleType>('SEDAN');
  const [make, setMake] = useState('');
  const [model, setModel] = useState('');
  const [color, setColor] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [editType, setEditType] = useState<VehicleType>('SEDAN');

  return (
    <div className="stack">
      <h2>My vehicles</h2>
      <Notice>
        The driver's vehicle is optional when booking. Plates are normalized and unique per region; plate and region cannot be edited (remove and re-add).
      </Notice>
      <ProblemView error={v.error} />
      <Card title="Add a vehicle">
        <form
          className="form inline"
          onSubmit={async (e) => {
            e.preventDefault();
            const r = await add.run(() =>
              profile.addVehicle({ licensePlate: plate, plateRegion: region, vehicleType: type, make: make || null, model: model || null, color: color || null }),
            );
            if (r) {
              setPlate('');
              v.reload();
            }
          }}
        >
          <Field label="Plate" hint="letters, digits, space, hyphen">
            <input value={plate} onChange={(e) => setPlate(e.target.value)} required minLength={2} maxLength={20} pattern="[A-Za-z0-9 \-]+" />
          </Field>
          <Field label="Region" hint="e.g. IN-KA">
            <input value={region} onChange={(e) => setRegion(e.target.value.toUpperCase())} required pattern="[A-Z]{2}(-[A-Z0-9]{1,3})?" />
          </Field>
          <Field label="Type">
            <select value={type} onChange={(e) => setType(e.target.value as VehicleType)}>
              {VEHICLE_TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </Field>
          <Field label="Make">
            <input value={make} onChange={(e) => setMake(e.target.value)} maxLength={60} />
          </Field>
          <Field label="Model">
            <input value={model} onChange={(e) => setModel(e.target.value)} maxLength={60} />
          </Field>
          <Field label="Color">
            <input value={color} onChange={(e) => setColor(e.target.value)} maxLength={30} />
          </Field>
          <button type="submit" disabled={add.busy}>
            Add
          </button>
        </form>
        <ProblemView error={add.error} />
      </Card>
      <ProblemView error={op.error} />
      <Card title="Vehicles (max 10, primary first)">
        {v.loading && !v.data && <Loading />}
        {v.data?.items.length === 0 && <p className="muted">None yet.</p>}
        <table className="table">
          <tbody>
            {v.data?.items.map((x) => (
              <tr key={x.id}>
                <td>
                  <strong>{x.licensePlate}</strong> <span className="muted">{x.plateRegion}</span> {x.primary && <Badge value="ACTIVE" />}
                  {x.primary && <span className="muted"> primary</span>}
                </td>
                <td>
                  {editing === x.id ? (
                    <select value={editType} onChange={(e) => setEditType(e.target.value as VehicleType)}>
                      {VEHICLE_TYPES.map((t) => (
                        <option key={t}>{t}</option>
                      ))}
                    </select>
                  ) : (
                    x.vehicleType
                  )}
                </td>
                <td>{[x.make, x.model, x.color].filter(Boolean).join(' ')}</td>
                <td className="actions">
                  {editing === x.id ? (
                    <button
                      onClick={async () => {
                        await op.run(() => profile.updateVehicle(x.id, { vehicleType: editType, make: x.make, model: x.model, color: x.color }));
                        setEditing(null);
                        v.reload();
                      }}
                    >
                      Save type
                    </button>
                  ) : (
                    <button
                      onClick={() => {
                        setEditing(x.id);
                        setEditType(x.vehicleType);
                      }}
                    >
                      Edit type
                    </button>
                  )}
                  {!x.primary && (
                    <button
                      onClick={async () => {
                        await op.run(() => profile.makePrimary(x.id));
                        v.reload();
                      }}
                    >
                      Make primary
                    </button>
                  )}
                  <button
                    onClick={async () => {
                      await op.run(() => profile.removeVehicle(x.id));
                      v.reload();
                    }}
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

// ------------------------------------------------------------------ payout accounts (host)
export function PayoutAccountsPage() {
  const a = useAuth();
  const list = useLoad(() => profile.payoutAccounts());
  const add = useAction();
  const del = useAction();
  const [type, setType] = useState('FAKE');
  const [token, setToken] = useState('fake_payout_account_1');
  return (
    <div className="stack">
      <h2>Payout account</h2>
      {!a.hasRole('HOST') && <Notice kind="warn">HOST only: the API answers 403 for drivers.</Notice>}
      <Notice>
        Only a processor-issued token is stored, never bank details. FAKE is accepted locally. New accounts stay <code>verified=false</code> until Payment
        confirms ownership; the fake processor pays any host, so earnings and payouts still work without verification.
      </Notice>
      <ProblemView error={list.error} />
      <Card title="Add account (max 3)">
        <form
          className="form inline"
          onSubmit={async (e) => {
            e.preventDefault();
            const r = await add.run(() => profile.addPayoutAccount(type, token));
            if (r) list.reload();
          }}
        >
          <Field label="Processor">
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option>FAKE</option>
              <option>STRIPE_CONNECT</option>
              <option>RAZORPAY_ROUTE</option>
            </select>
          </Field>
          <Field label="Token" hint="A-Z a-z 0-9 _ -, 6+ chars">
            <input value={token} onChange={(e) => setToken(e.target.value)} required minLength={6} pattern="[A-Za-z0-9_\-]+" />
          </Field>
          <button type="submit" disabled={add.busy}>
            Add
          </button>
        </form>
        <ProblemView error={add.error} />
      </Card>
      <Card title="Accounts">
        <ProblemView error={del.error} />
        {list.data?.items.length === 0 && <p className="muted">None.</p>}
        <table className="table">
          <tbody>
            {list.data?.items.map((x) => (
              <tr key={x.id}>
                <td>{x.processorType}</td>
                <td>{x.tokenHint}</td>
                <td>{x.verified ? 'verified' : 'unverified'}</td>
                <td>
                  <button
                    onClick={async () => {
                      await del.run(() => profile.deletePayoutAccount(x.id));
                      list.reload();
                    }}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
