import { Link } from 'react-router-dom';
import type { SearchResult } from '../api/types';
import { VEHICLE_FITS } from '../labels';
import { formatMoney } from '../lib';
import { Icon } from './Icon';
import { RatingInline } from './ui';

function distance(km: number): string {
  if (km < 0.1) return 'Under 100 m';
  if (km < 1) return `${Math.round(km * 10) * 100} m`;
  return `${km.toFixed(1)} km`;
}

export function ListingCard({
  r,
  query,
  active,
  onHover,
}: {
  r: SearchResult;
  /** start/end carried to the listing page so the booking widget is pre-filled. */
  query?: string;
  active?: boolean;
  onHover?: (id: string | null) => void;
}) {
  return (
    <Link
      to={`/listings/${r.listingId}${query ? `?${query}` : ''}`}
      className={`lcard ${active ? 'active' : ''}`}
      onMouseEnter={() => onHover?.(r.listingId)}
      onMouseLeave={() => onHover?.(null)}
    >
      <div className="lcard-img">
        {r.thumbnailUrl ? <img src={r.thumbnailUrl} alt="" loading="lazy" /> : <div className="noimg"><Icon name="car" size={36} /></div>}
        <div className="badges">
          {r.instantBook && (
            <span className="badge dark">
              <Icon name="bolt" size={12} /> Instant
            </span>
          )}
          {r.distanceKm != null && <span className="badge dark">{distance(r.distanceKm)}</span>}
        </div>
      </div>
      <div className="stack-xs">
        <div className="lcard-title">
          <span>{r.title}</span>
          <RatingInline avg={r.ratingAvg} count={r.ratingCount} />
        </div>
        <div className="lcard-meta">{r.address}</div>
        <div className="lcard-meta">{VEHICLE_FITS[r.maxVehicleType]}</div>
        <div className="lcard-price">
          <strong>{formatMoney(r.pricePerHour, r.currency)}</strong> / hour
          {r.estimatedTotal && <span className="muted"> · {formatMoney(r.estimatedTotal, r.currency)} total</span>}
        </div>
      </div>
    </Link>
  );
}

export function ListingCardSkeleton() {
  return (
    <div className="lcard">
      <div className="lcard-img skeleton" />
      <div className="skeleton" style={{ height: 16, width: '80%' }} />
      <div className="skeleton" style={{ height: 14, width: '60%' }} />
    </div>
  );
}
