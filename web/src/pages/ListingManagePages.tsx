import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { listings, profile } from '../api/endpoints';
import { DAYS, type AvailabilityRule, type Day, type Listing, type ListingTerms } from '../api/types';
import { DAY_LABEL } from '../labels';
import { QUARTER_TIMES, fromLocalInput, timeLabel } from '../lib';
import { Icon } from '../components/Icon';
import { DEFAULT_TERMS, ListingForm } from '../components/ListingForm';
import { PhotoUploader } from '../components/PhotoUploader';
import {
  Alert,
  Button,
  Card,
  Empty,
  Field,
  Loading,
  ProblemView,
  StatusBadge,
  Switch,
  Tabs,
  Time,
  toast,
  useAction,
  useDocumentTitle,
  useLoad,
} from '../components/ui';
import { forgetListing } from '../state/cache';
import { useAuth } from '../state/auth';

// ------------------------------------------------------------------ new listing
export function NewListingPage() {
  useDocumentTitle('New listing');
  const nav = useNavigate();
  const act = useAction();
  return (
    <div className="container narrow page">
      <Link to="/host/listings" className="small row" style={{ gap: 4, marginBottom: 16 }}>
        <Icon name="chevronLeft" size={15} /> Your listings
      </Link>
      <div className="page-head">
        <div>
          <h1>List your space</h1>
          <p className="sub">Start with the details. Photos and opening hours come next, then you submit it for review.</p>
        </div>
      </div>
      <ol className="steps">
        <li className="on">Details</li>
        <li>Photos</li>
        <li>Opening hours</li>
        <li>Submit</li>
      </ol>
      <div className="card card-pad">
        <ListingForm
          initial={DEFAULT_TERMS}
          submitLabel="Save and add photos"
          busy={act.busy}
          error={act.error}
          onSubmit={async (terms) => {
            const r = await act.run(() => listings.create(terms));
            if (r) nav(`/host/listings/${r.data.id}?tab=photos`, { replace: true });
          }}
        />
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ manage one listing
type TabName = 'photos' | 'hours' | 'details' | 'blocked';

export function ManageListingPage() {
  const { id = '' } = useParams();
  const [sp, setSp] = useSearchParams();
  const tab = (sp.get('tab') as TabName) || 'details';
  const [listing, setListing] = useState<Listing | null>(null);
  const [etag, setEtag] = useState('');
  const load = useLoad(async () => {
    const r = await listings.get(id);
    setListing(r.data);
    setEtag(r.headers.get('etag') ?? '');
    forgetListing(id);
    return r.data;
  }, [id]);
  const edit = useAction();
  useDocumentTitle(listing?.title ?? 'Listing');

  if (load.loading && !listing) return <Loading what="listing" />;
  if (!listing) {
    return (
      <div className="container narrow page">
        <ProblemView error={load.error} />
      </div>
    );
  }
  const l = listing;
  const locked = l.status === 'PENDING_REVIEW' || l.status === 'ARCHIVED';
  const ready = l.photos.filter((p) => p.status === 'READY').length;

  return (
    <div className="container page">
      <Link to="/host/listings" className="small row" style={{ gap: 4, marginBottom: 16 }}>
        <Icon name="chevronLeft" size={15} /> Your listings
      </Link>
      <div className="page-head">
        <div className="stack-xs">
          <div className="row">
            <h1 style={{ fontSize: '1.6rem' }}>{l.title}</h1>
            <StatusBadge kind="listing" value={l.status} />
          </div>
          <span className="sub">{l.address}</span>
        </div>
        <div className="grow" />
        <Link to={`/listings/${l.id}`} className="btn">
          <Icon name="eye" size={17} /> Preview
        </Link>
      </div>

      <div className="with-aside">
        <div className="stack">
          {l.status === 'REJECTED' && (
            <Alert kind="bad" title="Changes needed before this can go live">
              {l.rejectionReason ?? 'Our team asked for changes.'} Update the listing and submit it again.
            </Alert>
          )}
          {l.status === 'PENDING_REVIEW' && <Alert kind="warn" title="In review">We're checking your listing, usually within a day. Editing is paused until then.</Alert>}
          {l.status === 'PAUSED' && <Alert kind="info">Paused: drivers can't book this space. Existing bookings still go ahead.</Alert>}
          {l.status === 'ARCHIVED' && <Alert kind="info">Archived listings can't be edited or booked.</Alert>}

          <Tabs<TabName>
            value={tab}
            onChange={(t) => setSp({ tab: t }, { replace: true })}
            items={[
              { value: 'details', label: 'Details' },
              { value: 'photos', label: 'Photos', count: ready },
              { value: 'hours', label: 'Opening hours' },
              { value: 'blocked', label: 'Blocked dates', count: l.blackouts.length },
            ]}
          />
          {tab === 'details' && (
            <div className="card card-pad">
              <ListingForm
                key={etag + l.updatedAt}
                initial={{ ...l, description: l.description ?? '' } as ListingTerms}
                submitLabel="Save changes"
                busy={edit.busy}
                error={edit.error}
                disabled={locked}
                locationNote={l.status === 'PUBLISHED' || l.status === 'PAUSED' ? 'Changing the address or pin sends the listing back for review.' : undefined}
                onSubmit={async (terms) => {
                  const r = await edit.run(() => listings.update(l.id, etag, terms));
                  if (r) {
                    setListing(r.data);
                    setEtag(r.headers.get('etag') ?? etag);
                    forgetListing(l.id);
                    toast(r.data.status === 'PENDING_REVIEW' && l.status !== 'PENDING_REVIEW' ? 'Saved. The new location goes to review.' : 'Changes saved');
                  }
                }}
              />
            </div>
          )}
          {tab === 'photos' && <PhotosTab l={l} locked={locked} onChange={load.reload} onNext={() => setSp({ tab: 'hours' }, { replace: true })} />}
          {tab === 'hours' && <HoursTab l={l} locked={l.status === 'ARCHIVED'} onSaved={load.reload} />}
          {tab === 'blocked' && <BlockedTab l={l} locked={l.status === 'ARCHIVED'} onChange={load.reload} />}
        </div>
        <aside className="sticky">
          <PublishCard l={l} onChange={load.reload} />
        </aside>
      </div>
    </div>
  );
}

function PublishCard({ l, onChange }: { l: Listing; onChange: () => void }) {
  const a = useAuth();
  const me = useLoad(() => profile.me().catch(() => null), [a.accountId]);
  const act = useAction();
  const nav = useNavigate();
  const items = [
    { done: l.photos.some((p) => p.status === 'READY'), label: 'At least one photo', to: `?tab=photos` },
    { done: l.availabilityRules.length > 0, label: 'Opening hours', to: `?tab=hours` },
    { done: !!a.account?.phoneVerified, label: 'Verified phone number', to: '/settings/security' },
    { done: !!me.data?.hasPhoto, label: 'Your profile photo', to: '/settings/profile' },
  ];
  const allDone = items.every((i) => i.done);
  const run = async (fn: () => Promise<unknown>, msg: string) => {
    const r = await act.run(fn);
    if (r !== undefined) {
      forgetListing(l.id);
      toast(msg);
      onChange();
    }
  };

  return (
    <Card title={l.status === 'PUBLISHED' ? 'Your listing is live' : l.status === 'PAUSED' ? 'Paused' : 'Get ready to publish'}>
      <div className="stack">
        {['DRAFT', 'REJECTED'].includes(l.status) && (
          <>
            <ul className="checklist">
              {items.map((i) => (
                <li key={i.label} className={i.done ? 'done' : ''}>
                  <span className="tick">
                    <Icon name="check" size={13} />
                  </span>
                  <span className="label">{i.label}</span>
                  {!i.done && (
                    <Link className="small" to={i.to}>
                      Add
                    </Link>
                  )}
                </li>
              ))}
            </ul>
            <Button variant="primary" block icon="send" busy={act.busy} disabled={!allDone} onClick={() => void run(() => listings.submit(l.id), 'Submitted for review')}>
              Submit for review
            </Button>
            {!allDone && <p className="tiny muted">Finish the checklist to submit. Just verified your phone or photo? It can take a few seconds to register.</p>}
          </>
        )}
        {l.status === 'PENDING_REVIEW' && <p className="muted">We'll notify you as soon as it's approved.</p>}
        {l.status === 'PUBLISHED' && (
          <>
            <p className="muted">Drivers can find and book this space.</p>
            <Button block icon="pause" busy={act.busy} onClick={() => void run(() => listings.pause(l.id), 'Listing paused')}>
              Pause bookings
            </Button>
          </>
        )}
        {l.status === 'PAUSED' && (
          <Button variant="primary" block icon="play" busy={act.busy} onClick={() => void run(() => listings.resume(l.id), 'Listing is live again')}>
            Resume bookings
          </Button>
        )}
        <ProblemView error={act.error} compact />
        {l.status !== 'ARCHIVED' && (
          <button
            type="button"
            className="link-btn small"
            style={{ color: 'var(--bad)', alignSelf: 'flex-start' }}
            disabled={act.busy}
            onClick={async () => {
              if (!confirm('Archive this listing? It stops taking bookings for good. You can only archive when there are no active or upcoming bookings.')) return;
              const ok = await act.run(async () => {
                await listings.archive(l.id);
                return true;
              });
              if (ok) {
                forgetListing(l.id);
                toast('Listing archived');
                nav('/host/listings');
              }
            }}
          >
            Archive listing
          </button>
        )}
        <p className="tiny muted">
          Last updated <Time value={l.updatedAt} />
        </p>
      </div>
    </Card>
  );
}

function PhotosTab({ l, locked, onChange, onNext }: { l: Listing; locked: boolean; onChange: () => void; onNext: () => void }) {
  const op = useAction();
  const ready = l.photos.filter((p) => p.status === 'READY' && p.url).sort((a, b) => a.sortOrder - b.sortOrder);
  const makeCover = (photoId: string) =>
    op.run(async () => {
      await listings.reorderPhotos(l.id, [photoId, ...ready.map((p) => p.id).filter((x) => x !== photoId)]);
      onChange();
    });
  return (
    <div className="card card-pad stack">
      <div>
        <h3>Photos</h3>
        <p className="muted small">Show the entrance, the space itself and anything that helps drivers find it. The first photo is the cover. Up to 10.</p>
      </div>
      {ready.length > 0 && (
        <div className="photo-grid">
          {ready.map((p, i) => (
            <div key={p.id} className="photo-tile">
              <img src={p.url as string} alt="" />
              {i === 0 && <span className="badge dark cover">Cover</span>}
              {!locked && (
                <div className="tile-actions">
                  {i > 0 && (
                    <button type="button" title="Make cover photo" aria-label="Make cover photo" onClick={() => void makeCover(p.id)} disabled={op.busy}>
                      <Icon name="star" size={15} />
                    </button>
                  )}
                  <button
                    type="button"
                    title="Delete photo"
                    aria-label="Delete photo"
                    disabled={op.busy}
                    onClick={() =>
                      void op.run(async () => {
                        await listings.deletePhoto(l.id, p.id);
                        onChange();
                      })
                    }
                  >
                    <Icon name="trash" size={15} />
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
      <ProblemView error={op.error} compact />
      {locked ? (
        <p className="muted">Photos can't change while the listing is in review.</p>
      ) : ready.length < 10 ? (
        <PhotoUploader
          label={ready.length ? 'Add more photos' : 'Add photos'}
          hint="JPEG, PNG or WebP up to 10 MB each. Drag several at once."
          multiple
          maxBytes={10 * 1024 * 1024}
          createSlot={(ct, len) => listings.photoSlot(l.id, ct, len)}
          confirm={(slot) => listings.confirmPhoto(l.id, slot.photoId as string)}
          onDone={onChange}
        />
      ) : null}
      {ready.length > 0 && l.availabilityRules.length === 0 && (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <Button variant="primary" onClick={onNext}>
            Next: opening hours <Icon name="arrowRight" size={16} />
          </Button>
        </div>
      )}
    </div>
  );
}

type DayWindow = { open: boolean; start: string; end: string };
function toWindows(rules: AvailabilityRule[]): Record<Day, DayWindow> {
  const out = {} as Record<Day, DayWindow>;
  for (const d of DAYS) {
    const r = rules.find((x) => x.dayOfWeek === d);
    out[d] = r ? { open: true, start: r.startTime, end: r.endTime } : { open: false, start: '08:00', end: '20:00' };
  }
  return out;
}
const END_TIMES = [...QUARTER_TIMES.slice(1), '24:00'];

function HoursTab({ l, locked, onSaved }: { l: Listing; locked: boolean; onSaved: () => void }) {
  const [w, setW] = useState(() => toWindows(l.availabilityRules));
  useEffect(() => {
    setW(toWindows(l.availabilityRules));
  }, [l.availabilityRules]);
  const act = useAction();
  const multi = DAYS.some((d) => l.availabilityRules.filter((r) => r.dayOfWeek === d).length > 1);
  const preset = (open: (d: Day) => boolean, start: string, end: string) => setW(Object.fromEntries(DAYS.map((d) => [d, { open: open(d), start, end }])) as Record<Day, DayWindow>);
  const invalid = DAYS.filter((d) => w[d].open && w[d].end !== '24:00' && w[d].end <= w[d].start);
  return (
    <div className="card card-pad stack">
      <div>
        <h3>Opening hours</h3>
        <p className="muted small">When drivers can park, in {l.timeZone} time. Use blocked dates for one-off closures.</p>
      </div>
      <div className="chips">
        <button type="button" className="chip" onClick={() => preset(() => true, '00:00', '24:00')} disabled={locked}>
          24/7
        </button>
        <button type="button" className="chip" onClick={() => preset((d) => !['SATURDAY', 'SUNDAY'].includes(d), '08:00', '20:00')} disabled={locked}>
          Weekdays 8 AM – 8 PM
        </button>
        <button type="button" className="chip" onClick={() => preset(() => true, '07:00', '22:00')} disabled={locked}>
          Every day 7 AM – 10 PM
        </button>
      </div>
      {multi && <Alert kind="warn">Some days have more than one time window. Saving here replaces them with one window per day.</Alert>}
      <div>
        {DAYS.map((d) => (
          <div key={d} className="week-row">
            <label className="row">
              <Switch checked={w[d].open} disabled={locked} onChange={(v) => setW({ ...w, [d]: { ...w[d], open: v } })} label={`${DAY_LABEL[d]} open`} />
              <span className="strong">{DAY_LABEL[d]}</span>
            </label>
            <div className="times">
              {w[d].open ? (
                <>
                  <select value={w[d].start} disabled={locked} onChange={(e) => setW({ ...w, [d]: { ...w[d], start: e.target.value } })} aria-label={`${DAY_LABEL[d]} opens`}>
                    {QUARTER_TIMES.map((t) => (
                      <option key={t} value={t}>
                        {timeLabel(t)}
                      </option>
                    ))}
                  </select>
                  <span className="muted">to</span>
                  <select value={w[d].end} disabled={locked} onChange={(e) => setW({ ...w, [d]: { ...w[d], end: e.target.value } })} aria-label={`${DAY_LABEL[d]} closes`}>
                    {END_TIMES.map((t) => (
                      <option key={t} value={t}>
                        {timeLabel(t)}
                      </option>
                    ))}
                  </select>
                  {w[d].start === '00:00' && w[d].end === '24:00' && <span className="badge brand">All day</span>}
                </>
              ) : (
                <span className="muted">Closed</span>
              )}
            </div>
          </div>
        ))}
      </div>
      {invalid.length > 0 && <p className="small bad-text">Closing time must be after opening time on {invalid.map((d) => DAY_LABEL[d]).join(', ')}.</p>}
      <ProblemView error={act.error} compact />
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        <Button
          variant="primary"
          busy={act.busy}
          disabled={locked || invalid.length > 0}
          onClick={async () => {
            const rules: AvailabilityRule[] = DAYS.filter((d) => w[d].open).map((d) => ({ dayOfWeek: d, startTime: w[d].start, endTime: w[d].end }));
            const r = await act.run(() => listings.setAvailability(l.id, rules));
            if (r) {
              toast('Opening hours saved');
              onSaved();
            }
          }}
        >
          Save opening hours
        </Button>
      </div>
    </div>
  );
}

function BlockedTab({ l, locked, onChange }: { l: Listing; locked: boolean; onChange: () => void }) {
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [reason, setReason] = useState('');
  const add = useAction();
  const del = useAction();
  return (
    <div className="card card-pad stack">
      <div>
        <h3>Blocked dates</h3>
        <p className="muted small">Close the space for a period, e.g. when you need it yourself. Existing bookings are not affected.</p>
      </div>
      {l.blackouts.length === 0 ? (
        <Empty icon="calendar" title="No blocked dates" />
      ) : (
        <div className="table-wrap">
          <table className="table">
            <tbody>
              {l.blackouts.map((b) => (
                <tr key={b.id}>
                  <td>
                    <Time value={b.start} /> – <Time value={b.end} />
                  </td>
                  <td className="muted">{b.reason}</td>
                  <td style={{ textAlign: 'right' }}>
                    <Button
                      size="sm"
                      variant="ghost"
                      icon="trash"
                      disabled={locked}
                      busy={del.busy}
                      onClick={async () => {
                        await del.run(() => listings.deleteBlackout(l.id, b.id));
                        onChange();
                      }}
                    >
                      Remove
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <ProblemView error={del.error} compact />
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const r = await add.run(() => listings.addBlackout(l.id, { start: fromLocalInput(start), end: fromLocalInput(end), reason: reason.trim() || undefined }));
          if (r) {
            setStart('');
            setEnd('');
            setReason('');
            toast('Dates blocked');
            onChange();
          }
        }}
      >
        <fieldset disabled={locked} className="stack">
          <div className="grid-2">
            <Field label="From">
              <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} required />
            </Field>
            <Field label="Until">
              <input type="datetime-local" value={end} min={start} onChange={(e) => setEnd(e.target.value)} required />
            </Field>
          </div>
          <Field label="Note (only you see this)">
            <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} placeholder="e.g. Family visiting" />
          </Field>
          <ProblemView error={add.error} compact />
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <Button type="submit" variant="primary" busy={add.busy}>
              Block these dates
            </Button>
          </div>
        </fieldset>
      </form>
    </div>
  );
}
