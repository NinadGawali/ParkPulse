import { useCallback, useEffect, useState } from 'react';
import { fetchDevCodes, type DevCode } from '../api/endpoints';
import { Card, Copy } from './ui';

/**
 * LOCAL ONLY: lists the one-time codes Notification wrote to logs/notification.log ("DEV ONLY body").
 * Served by a Vite dev-server middleware (web/vite.config.ts); not available in a production build.
 * Emails are masked in the log, so match by time and purpose (the newest matching code is the live one).
 */
export function DevCodes({ purpose, onPick }: { purpose?: 'email' | 'phone' | 'password reset'; onPick?: (c: DevCode) => void }) {
  const [items, setItems] = useState<DevCode[]>([]);
  const [info, setInfo] = useState<{ available: boolean; error?: string }>({ available: true });
  const load = useCallback(async () => {
    try {
      const r = await fetchDevCodes();
      setItems(r.items);
      setInfo({ available: r.available, error: r.error });
    } catch (e) {
      setInfo({ available: false, error: e instanceof Error ? e.message : String(e) });
    }
  }, []);
  useEffect(() => {
    void load();
    const id = setInterval(() => void load(), 4000);
    return () => clearInterval(id);
  }, [load]);
  const shown = purpose ? items.filter((i) => i.purpose === purpose) : items;
  return (
    <Card
      title="Dev helper: recent codes from the Notification log"
      actions={
        <button type="button" className="link" onClick={() => void load()}>
          refresh
        </button>
      }
    >
      {!info.available && (
        <p className="muted">
          Could not read <code>logs/notification.log</code> ({info.error}). Run the dev server from <code>web/</code> inside the repo, with the
          stack started by <code>infra/scripts/run-local.sh</code>. Or <code>grep "DEV ONLY" logs/notification.log</code>.
        </p>
      )}
      {info.available && shown.length === 0 && <p className="muted">No codes logged yet. Request one, it appears here within a few seconds.</p>}
      {shown.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Logged at</th>
              <th>For</th>
              <th>Sent to (masked)</th>
              <th>Code / token</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {shown.slice(0, purpose ? 5 : 15).map((c, i) => (
              <tr key={c.at + c.code + i}>
                <td>{c.at.replace('T', ' ').replace(/\.\d+Z$/, 'Z')}</td>
                <td>{c.purpose}</td>
                <td>
                  {c.channel} {c.to}
                </td>
                <td>
                  <code>{c.code.length > 14 ? c.code.slice(0, 14) + '...' : c.code}</code> <Copy text={c.code} />
                </td>
                <td>
                  {onPick && (
                    <button type="button" onClick={() => onPick(c)}>
                      Use
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
