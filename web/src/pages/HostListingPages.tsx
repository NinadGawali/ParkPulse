import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { listings } from '../api/endpoints';
import { DAYS, type AvailabilityRule, type Listing, type ListingStatus, type ListingTerms } from '../api/types';
import { usePaged } from '../components/paged';
import { Badge, Card, Field, Json, LoadMore, Loading, Money, Notice, ProblemView, Time, useAction, useLoad } from '../components/ui';
import { DEFAULT_TERMS, ListingForm } from '../components/ListingForm';
import { PhotoUploader } from '../components/PhotoUploader';
import { useAuth } from '../state/auth';

const STATUSES: ListingStatus[] = ['DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'PAUSED', 'REJECTED', 'ARCHIVED'];

export function HostListingsPage() {
  const [status, setStatus] = useState('');
  const p = usePaged((c) => listings.mine(status || undefined, c), [status]);
  return (
    <div className="stack">
      <div className="row">
        <h2>My listings</h2>
        <div className="grow" />
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter by status">
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
        <Link className="button" to="/host/listings/new">
          New listing
        </Link>
      </div>
      <ProblemView error={p.error} />
      {p.loading && p.items.length === 0 && <Loading />}
      {!p.loading && p.items.length === 0 && !p.error && <p className="muted">No listings. Create one to start.</p>}
      <div className="cards">
        {p.items.map((l) => (
          <Link key={l.id} to={`/host/listings/${l.id}`} className="listing-card">
            {l.thumbnailUrl ? <img src={l.thumbnailUrl} alt="" /> : <div className="noimg">no photo</div>}
            <div>
              <strong>{l.title}</strong>
              <div>
                <Badge value={l.status} /> <Money value={l.pricePerHour} currency={l.currency} />/h
              </div>
              <code className="muted">{l.id}</code>
            </div>
          </Link>
        ))}
      </div>
      <LoadMore cursor={p.next} onMore={p.more} busy={p.loading} />
    </div>
  );
}

export function ListingNewPage() {
  const nav = useNavigate();
  const act = useAction();
  return (
    <div className="stack">
      <h2>New listing</h2>
      <Notice>
        Creates a DRAFT. Then add a photo and availability rules, make sure you are an eligible host (HOST role, verified phone, profile photo) and submit for
        review. Check your profile photo and phone on the <Link to="/account">Account</Link> and <Link to="/profile">Profile</Link> pages.
      </Notice>
      <Card>
        <ListingForm
          initial={DEFAULT_TERMS}
          submitLabel="Create draft"
          busy={act.busy}
          error={act.error}
          onSubmit={async (terms) => {
            const r = await act.run(() => listings.create(terms));
            if (r) nav(`/host/listings/${r.data.id}`);
          }}
        />
      </Card>
    </div>
  );
}

// ------------------------------------------------------------------ manage one listing
export function ListingManagePage() {
  const { id = '' } = useParams();
  const a = useAuth();
  const [listing, setListing] = useState<Listing | null>(null);
  const [etag, setEtag] = useState<string>('');
  const load = useLoad(async () => {
    const r = await listings.get(id);
    setListing(r.data);
    setEtag(r.headers.get('etag') ?? '');
    return r.data;
  }, [id]);
  const edit = useAction();
  const life = useAction();
  const [msg, setMsg] = useState<string | null>(null);
  const nav = useNavigate();

  const l = listing;
  const editable = !!l && l.status !== 'PENDING_REVIEW' && l.status !== 'ARCHIVED';

  const lifecycle = async (name: string, fn: () => Promise<unknown>) => {
    setMsg(null);
    const r = await life.run(fn);
    if (r !== undefined || name === 'archive') {
      setMsg(`${name} done.`);
      load.reload();
    }
  };

  if (load.loading && !l) return <Loading what="listing" />;
  if (!l) return <ProblemView error={load.error} />;

  return (
    <div className="stack">
      <div className="row">
        <h2>{l.title}</h2> <Badge value={l.status} />
        <div className="grow" />
        <Link to="/host/listings">Back to listings</Link>
      </div>
      <p className="muted">
        <code>{l.id}</code> | ETag <code>{etag || 'n/a'}</code> | updated <Time value={l.updatedAt} />
      </p>
      {l.status === 'REJECTED' && <Notice kind="bad">Rejected by admin: {l.rejectionReason ?? '(no reason)'}. Edit and submit again.</Notice>}
      {l.status === 'PENDING_REVIEW' && (
        <Notice kind="warn">
          In review: details and photos are locked. An admin must approve it (see <Link to="/dev">Dev helper</Link> to get an admin).
        </Notice>
      )}
      {l.status === 'PUBLISHED' && (
        <Notice kind="ok">
          Published. <Link to={`/listings/${l.id}`}>Open the public page</Link>. Edits to address/location send it back to review.
        </Notice>
      )}
      {msg && <Notice kind="ok">{msg}</Notice>}
      <ProblemView error={life.error} />

      <Card title="Lifecycle">
        <div className="row wrap">
          <button disabled={life.busy || !['DRAFT', 'REJECTED'].includes(l.status)} onClick={() => lifecycle('submit', () => listings.submit(l.id))}>
            Submit for review
          </button>
          <button disabled={life.busy || l.status !== 'PUBLISHED'} onClick={() => lifecycle('pause', () => listings.pause(l.id))}>
            Pause
          </button>
          <button disabled={life.busy || l.status !== 'PAUSED'} onClick={() => lifecycle('resume', () => listings.resume(l.id))}>
            Resume
          </button>
          <button
            disabled={life.busy || l.status === 'ARCHIVED'}
            onClick={async () => {
              if (!confirm('Archive this listing? It is blocked with 409 while active/upcoming bookings exist.')) return;
              await lifecycle('archive', async () => {
                await listings.archive(l.id);
                return true;
              });
            }}
          >
            Archive
          </button>
        </div>
        <p className="muted">
          Submit needs: at least 1 confirmed photo, at least 1 availability rule, and an eligible host (HOST role, phone {a.account?.phoneVerified ? 'verified' : 'NOT verified'},
          profile photo). The host-eligibility projection arrives over Kafka a few seconds after you verify/upload, so a first submit can say 422 until it lands: retry.
          Unmet requirements are listed in the problem detail.
        </p>
      </Card>

      <Card title="Details">
        {!editable && <p className="muted">Not editable while {l.status}.</p>}
        <ListingForm
          key={etag + l.updatedAt}
          initial={{ ...l, description: l.description ?? '' } as ListingTerms}
          submitLabel="Save changes (PATCH with If-Match)"
          busy={edit.busy}
          error={edit.error}
          disabled={!editable}
          onSubmit={async (terms) => {
            const r = await edit.run(() => listings.update(l.id, etag, terms));
            if (r) {
              setListing(r.data);
              setEtag(r.headers.get('etag') ?? etag);
              setMsg('Saved.');
            }
          }}
        />
      </Card>

      <PhotosCard l={l} editable={editable} onChange={load.reload} />
      <AvailabilityCard l={l} onChange={load.reload} />
      <BlackoutsCard l={l} onChange={load.reload} />
      <details>
        <summary>Raw listing JSON</summary>
        <Json value={l} />
      </details>
      <button className="link" onClick={() => nav('/host/bookings')}>
        See booking requests
      </button>
    </div>
  );
}

function PhotosCard({ l, editable, onChange }: { l: Listing; editable: boolean; onChange: () => void }) {
  const del = useAction();
  return (
    <Card title={`Photos (${l.photos.filter((p) => p.status === 'READY').length} ready, max 10)`}>
      <div className="thumbs">
        {l.photos.map((p) => (
          <figure key={p.id}>
            {p.url ? <img src={p.url} alt="" /> : <div className="noimg">pending</div>}
            <figcaption>
              <Badge value={p.status} />
              <button
                className="link"
                disabled={!editable || del.busy}
                onClick={async () => {
                  await del.run(() => listings.deletePhoto(l.id, p.id));
                  onChange();
                }}
              >
                delete
              </button>
            </figcaption>
          </figure>
        ))}
      </div>
      <ProblemView error={del.error} />
      {editable ? (
        <PhotoUploader
          label="Add a photo (jpeg/png/webp, max 10 MB)"
          maxBytes={10 * 1024 * 1024}
          createSlot={(ct, len) => listings.photoSlot(l.id, ct, len)}
          confirm={(slot) => listings.confirmPhoto(l.id, slot.photoId as string)}
          onDone={onChange}
        />
      ) : (
        <p className="muted">Photos cannot change while in review.</p>
      )}
    </Card>
  );
}

type Window = { open: boolean; start: string; end: string };
function toWindows(rules: AvailabilityRule[]): Record<string, Window> {
  const out: Record<string, Window> = {};
  for (const d of DAYS) {
    const r = rules.find((x) => x.dayOfWeek === d);
    out[d] = r ? { open: true, start: r.startTime, end: r.endTime } : { open: false, start: '08:00', end: '20:00' };
  }
  return out;
}
function AvailabilityCard({ l, onChange }: { l: Listing; onChange: () => void }) {
  const [w, setW] = useState(() => toWindows(l.availabilityRules));
  useEffect(() => setW(toWindows(l.availabilityRules)), [l.availabilityRules]);
  const act = useAction();
  const preset = (open: (d: string) => boolean, start: string, end: string) =>
    setW(Object.fromEntries(DAYS.map((d) => [d, { open: open(d), start, end }])));
  return (
    <Card title="Weekly availability (listing local time)">
      <div className="row wrap">
        <button type="button" onClick={() => preset(() => true, '00:00', '24:00')}>
          Preset: 24/7
        </button>
        <button type="button" onClick={() => preset((d) => !['SATURDAY', 'SUNDAY'].includes(d), '08:00', '20:00')}>
          Preset: weekdays 08-20
        </button>
        <button type="button" onClick={() => preset(() => false, '08:00', '20:00')}>
          Clear
        </button>
      </div>
      <table className="table">
        <tbody>
          {DAYS.map((d) => (
            <tr key={d}>
              <td>
                <label className="check">
                  <input type="checkbox" checked={w[d].open} onChange={(e) => setW({ ...w, [d]: { ...w[d], open: e.target.checked } })} /> {d}
                </label>
              </td>
              <td>
                <input
                  type="text"
                  value={w[d].start}
                  disabled={!w[d].open}
                  onChange={(e) => setW({ ...w, [d]: { ...w[d], start: e.target.value } })}
                  size={5}
                  aria-label={`${d} start`}
                />{' '}
                to{' '}
                <input
                  type="text"
                  value={w[d].end}
                  disabled={!w[d].open}
                  onChange={(e) => setW({ ...w, [d]: { ...w[d], end: e.target.value } })}
                  size={5}
                  aria-label={`${d} end`}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">HH:MM, 24:00 = end of day. One window per day here (the API allows several non-overlapping ones). Times are in {l.timeZone}.</p>
      <button
        disabled={act.busy}
        onClick={async () => {
          const rules: AvailabilityRule[] = DAYS.filter((d) => w[d].open).map((d) => ({ dayOfWeek: d, startTime: w[d].start, endTime: w[d].end }));
          const r = await act.run(() => listings.setAvailability(l.id, rules));
          if (r) onChange();
        }}
      >
        Save availability
      </button>
      <ProblemView error={act.error} />
    </Card>
  );
}

function BlackoutsCard({ l, onChange }: { l: Listing; onChange: () => void }) {
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [reason, setReason] = useState('');
  const add = useAction();
  const del = useAction();
  return (
    <Card title="Blackouts">
      <p className="muted">Blackouts do not cancel existing bookings. They must end in the future and start within 2 years.</p>
      <table className="table">
        <tbody>
          {l.blackouts.map((b) => (
            <tr key={b.id}>
              <td>
                <Time value={b.start} /> to <Time value={b.end} />
              </td>
              <td>{b.reason}</td>
              <td>
                <button
                  onClick={async () => {
                    await del.run(() => listings.deleteBlackout(l.id, b.id));
                    onChange();
                  }}
                >
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <ProblemView error={del.error} />
      <form
        className="form inline"
        onSubmit={async (e) => {
          e.preventDefault();
          const r = await add.run(() =>
            listings.addBlackout(l.id, { start: new Date(start).toISOString(), end: new Date(end).toISOString(), reason: reason || undefined }),
          );
          if (r) {
            setReason('');
            onChange();
          }
        }}
      >
        <Field label="Start">
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} required />
        </Field>
        <Field label="End">
          <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} required />
        </Field>
        <Field label="Reason">
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} />
        </Field>
        <button type="submit" disabled={add.busy}>
          Add blackout
        </button>
      </form>
      <ProblemView error={add.error} />
    </Card>
  );
}
