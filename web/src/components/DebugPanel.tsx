import { useState } from 'react';
import { clearDebug, useDebugEntries } from '../state/debug';
import type { DebugEntry } from '../api/client';

function statusClass(s: number): string {
  if (s === 0) return 'bad';
  if (s < 300) return 'ok';
  if (s < 400) return 'info';
  if (s < 500) return 'warn';
  return 'bad';
}

function Detail({ e }: { e: DebugEntry }) {
  return (
    <div className="debug-detail">
      <div className="debug-cols">
        <div>
          <h4>
            Request: {e.method} {e.url}
          </h4>
          <pre>{JSON.stringify(e.requestHeaders, null, 2)}</pre>
          {e.requestBody !== undefined && <pre>{JSON.stringify(e.requestBody, null, 2)}</pre>}
        </div>
        <div>
          <h4>
            Response: {e.status || 'network error'} in {e.ms} ms {e.note ? `(${e.note})` : ''}
          </h4>
          <pre>{JSON.stringify(e.responseHeaders, null, 2)}</pre>
          {e.responseBody !== undefined && <pre>{JSON.stringify(e.responseBody, null, 2)}</pre>}
        </div>
      </div>
    </div>
  );
}

export function DebugPanel() {
  const entries = useDebugEntries();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);
  const last = entries[0];
  const shown = entries.find((e) => e.id === selected) ?? last;
  return (
    <aside className={`debug ${open ? 'open' : ''}`} aria-label="Last request and response">
      <button type="button" className="debug-toggle" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        <span>{open ? '▼' : '▲'} Last request / response</span>
        {last ? (
          <span className="debug-last">
            <span className={`pill ${statusClass(last.status)}`}>{last.status || 'ERR'}</span> {last.method} {last.url} ({last.ms} ms)
          </span>
        ) : (
          <span className="muted"> no requests yet</span>
        )}
      </button>
      {open && (
        <div className="debug-body">
          <div className="debug-list">
            <button type="button" className="link" onClick={clearDebug}>
              clear
            </button>
            {entries.map((e) => (
              <button
                type="button"
                key={e.id}
                className={`debug-row ${shown?.id === e.id ? 'sel' : ''}`}
                onClick={() => setSelected(e.id)}
              >
                <span className={`pill ${statusClass(e.status)}`}>{e.status || 'ERR'}</span> {e.method} {e.url.replace(/^https?:\/\/[^/]+/, '')}
              </button>
            ))}
          </div>
          {shown ? <Detail e={shown} /> : <p className="muted">Nothing yet.</p>}
        </div>
      )}
    </aside>
  );
}
