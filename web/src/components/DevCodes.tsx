import { useCallback, useEffect, useState } from 'react';
import { fetchDevCodes, type DevCode } from '../api/endpoints';
import { Copy } from './ui';

/**
 * DEV SERVER ONLY: lists the one-time codes Notification wrote to logs/notification.log ("DEV ONLY body"),
 * so verification can be finished without a real inbox. Callers render it only when import.meta.env.DEV.
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
  const shown = (purpose ? items.filter((i) => i.purpose === purpose) : items).slice(0, purpose ? 3 : 15);
  return (
    <div className="devbox">
      <div className="row between">
        <strong>Dev only · codes from logs/notification.log</strong>
        <button type="button" className="link-btn" onClick={() => void load()}>
          Refresh
        </button>
      </div>
      {!info.available && <p className="muted">Could not read the log ({info.error}). Start the stack with infra/scripts/run-local.sh.</p>}
      {info.available && shown.length === 0 && <p className="muted">No codes yet. Request one; it shows up here within seconds.</p>}
      {shown.length > 0 && (
        <table className="table">
          <tbody>
            {shown.map((c, i) => (
              <tr key={c.at + c.code + i}>
                <td className="nowrap">{new Date(c.at).toLocaleTimeString()}</td>
                <td>{c.purpose}</td>
                <td className="muted">{c.to}</td>
                <td>
                  <code>{c.code.length > 14 ? c.code.slice(0, 14) + '…' : c.code}</code> <Copy text={c.code} />
                </td>
                <td>
                  {onPick && (
                    <button type="button" className="btn btn-sm" onClick={() => onPick(c)}>
                      Use
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
