import { earliestStart, joinLocal, QUARTER_TIMES, splitLocal, timeLabel } from '../lib';
import { useNow } from './ui';

const MIN = 60_000;

/**
 * Arrival and departure on Booking's 15-minute grid, in the browser's time zone. Changing the arrival keeps
 * the current duration; times before the earliest bookable start (or less than 30 min after arrival) are hidden.
 */
export function WhenPicker({
  start,
  end,
  onChange,
  layout = 'grid',
}: {
  start: string;
  end: string;
  onChange: (startIso: string, endIso: string) => void;
  layout?: 'grid' | 'inline';
}) {
  const now = useNow(30_000);
  const s = new Date(start);
  const e = new Date(end);
  const sp = splitLocal(s);
  const ep = splitLocal(e);
  const minStart = earliestStart(now);
  const today = splitLocal(new Date(minStart)).date;
  const duration = Math.max(e.getTime() - s.getTime(), 30 * MIN) || 120 * MIN;

  const setStart = (date: string, time: string) => {
    if (!date || !time) return;
    let ns = joinLocal(date, time).getTime();
    if (ns < minStart) ns = minStart;
    onChange(new Date(ns).toISOString(), new Date(ns + duration).toISOString());
  };
  const setEnd = (date: string, time: string) => {
    if (!date || !time) return;
    let ne = joinLocal(date, time).getTime();
    if (ne < s.getTime() + 30 * MIN) ne = s.getTime() + 30 * MIN;
    onChange(start, new Date(ne).toISOString());
  };

  const startTimes = QUARTER_TIMES.filter((t) => t === sp.time || joinLocal(sp.date, t).getTime() >= minStart);
  const endTimes = QUARTER_TIMES.filter((t) => t === ep.time || joinLocal(ep.date, t).getTime() >= s.getTime() + 30 * MIN);

  const startDate = (
    <input type="date" aria-label="Arrival date" value={sp.date} min={today} onChange={(ev) => setStart(ev.target.value, sp.time)} />
  );
  const startTime = (
    <select aria-label="Arrival time" value={sp.time} onChange={(ev) => setStart(sp.date, ev.target.value)}>
      {startTimes.map((t) => (
        <option key={t} value={t}>
          {timeLabel(t)}
        </option>
      ))}
    </select>
  );
  const endDate = <input type="date" aria-label="Departure date" value={ep.date} min={sp.date} onChange={(ev) => setEnd(ev.target.value, ep.time)} />;
  const endTime = (
    <select aria-label="Departure time" value={ep.time} onChange={(ev) => setEnd(ep.date, ev.target.value)}>
      {endTimes.map((t) => (
        <option key={t} value={t}>
          {timeLabel(t)}
        </option>
      ))}
    </select>
  );

  if (layout === 'inline') {
    return (
      <div className="when-box">
        <span className="small strong">Arrive</span>
        {startDate}
        {startTime}
        <span className="small strong">Leave</span>
        {endDate}
        {endTime}
      </div>
    );
  }
  return (
    <div className="when-grid">
      <div className="cell">
        <label>Arrive</label>
        {startDate}
      </div>
      <div className="cell">
        <label>Time</label>
        {startTime}
      </div>
      <div className="cell">
        <label>Leave</label>
        {endDate}
      </div>
      <div className="cell">
        <label>Time</label>
        {endTime}
      </div>
    </div>
  );
}
