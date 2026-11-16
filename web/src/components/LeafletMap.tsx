import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import iconUrl from 'leaflet/dist/images/marker-icon.png';
import iconRetinaUrl from 'leaflet/dist/images/marker-icon-2x.png';
import shadowUrl from 'leaflet/dist/images/marker-shadow.png';

L.Icon.Default.mergeOptions({ iconUrl, iconRetinaUrl, shadowUrl });

/** OpenStreetMap's public tiles suit light use only; production sets VITE_MAP_TILE_URL to a tile provider. */
const TILE_URL: string = import.meta.env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

export interface MapMarker {
  id?: string;
  lat: number;
  lng: number;
  /** Shown as a pill on the map (e.g. a price). Plain text, escaped. */
  pill?: string;
  /** Popup text for a classic pin. */
  label?: string;
  onClick?: () => void;
}

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string);

/**
 * OpenStreetMap tiles via Leaflet (tiles need internet; the app works without them).
 * `onPick` makes the map clickable (and the pick marker draggable). `onMoveEnd` reports the centre after
 * the user pans or zooms. `fitToMarkers` zooms to show every marker whenever the set changes.
 */
export function LeafletMap({
  center,
  zoom = 13,
  markers = [],
  highlight,
  pick,
  onPick,
  onMoveEnd,
  fitToMarkers,
  height = 320,
  className,
}: {
  center: { lat: number; lng: number };
  zoom?: number;
  markers?: MapMarker[];
  highlight?: string | null;
  pick?: { lat: number; lng: number } | null;
  onPick?: (lat: number, lng: number) => void;
  onMoveEnd?: (center: { lat: number; lng: number }, byUser: boolean) => void;
  fitToMarkers?: boolean;
  height?: number | string;
  className?: string;
}) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const layer = useRef<L.LayerGroup | null>(null);
  const pins = useRef<Map<string, L.Marker>>(new Map());
  const pickMarker = useRef<L.Marker | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  const onMoveRef = useRef(onMoveEnd);
  onMoveRef.current = onMoveEnd;
  const programmatic = useRef(false);

  useEffect(() => {
    if (!el.current || map.current) return;
    const m = L.map(el.current, { scrollWheelZoom: true }).setView([center.lat, center.lng], zoom);
    L.tileLayer(TILE_URL, {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(m);
    layer.current = L.layerGroup().addTo(m);
    m.on('click', (e: L.LeafletMouseEvent) => onPickRef.current?.(Number(e.latlng.lat.toFixed(6)), Number(e.latlng.lng.toFixed(6))));
    m.on('moveend', () => {
      const c = m.getCenter();
      onMoveRef.current?.({ lat: Number(c.lat.toFixed(6)), lng: Number(c.lng.toFixed(6)) }, !programmatic.current);
      programmatic.current = false;
    });
    map.current = m;
    // The container may have been laid out after creation (flex/grid parents).
    setTimeout(() => m.invalidateSize(), 0);
    return () => {
      m.remove();
      map.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    const cur = m.getCenter();
    if (Math.abs(cur.lat - center.lat) > 1e-6 || Math.abs(cur.lng - center.lng) > 1e-6) {
      programmatic.current = true;
      m.setView([center.lat, center.lng], m.getZoom());
    }
  }, [center.lat, center.lng]);

  useEffect(() => {
    const g = layer.current;
    const m = map.current;
    if (!g || !m) return;
    g.clearLayers();
    pins.current.clear();
    for (const mk of markers) {
      const marker = mk.pill
        ? L.marker([mk.lat, mk.lng], {
            icon: L.divIcon({ className: 'pin-wrap', html: `<span class="price-pin">${escapeHtml(mk.pill)}</span>`, iconSize: [0, 0] }),
          })
        : L.marker([mk.lat, mk.lng]);
      marker.addTo(g);
      if (mk.label) marker.bindPopup(escapeHtml(mk.label));
      if (mk.onClick) marker.on('click', mk.onClick);
      if (mk.id) pins.current.set(mk.id, marker);
    }
    if (fitToMarkers && markers.length > 0) {
      programmatic.current = true;
      if (markers.length === 1) m.setView([markers[0].lat, markers[0].lng], Math.max(m.getZoom(), 14));
      else m.fitBounds(L.latLngBounds(markers.map((x) => [x.lat, x.lng] as [number, number])), { padding: [40, 40], maxZoom: 15 });
    }
  }, [markers, fitToMarkers]);

  useEffect(() => {
    pins.current.forEach((marker, id) => {
      const node = marker.getElement()?.querySelector('.price-pin');
      if (node) node.classList.toggle('hot', id === highlight);
      if (id === highlight) marker.setZIndexOffset(1000);
      else marker.setZIndexOffset(0);
    });
  }, [highlight, markers]);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    if (pick) {
      if (pickMarker.current) pickMarker.current.setLatLng([pick.lat, pick.lng]);
      else {
        const pm = L.marker([pick.lat, pick.lng], { draggable: !!onPickRef.current }).addTo(m);
        pm.on('dragend', () => {
          const ll = pm.getLatLng();
          onPickRef.current?.(Number(ll.lat.toFixed(6)), Number(ll.lng.toFixed(6)));
        });
        pickMarker.current = pm;
      }
    } else if (pickMarker.current) {
      pickMarker.current.remove();
      pickMarker.current = null;
    }
  }, [pick]);

  return <div ref={el} className={`map ${className ?? ''}`} style={{ height }} />;
}
