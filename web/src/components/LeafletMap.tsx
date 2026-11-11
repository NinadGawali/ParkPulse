import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import iconUrl from 'leaflet/dist/images/marker-icon.png';
import iconRetinaUrl from 'leaflet/dist/images/marker-icon-2x.png';
import shadowUrl from 'leaflet/dist/images/marker-shadow.png';

L.Icon.Default.mergeOptions({ iconUrl, iconRetinaUrl, shadowUrl });

export interface MapMarker {
  lat: number;
  lng: number;
  label?: string;
  onClick?: () => void;
}

/**
 * OpenStreetMap tiles via Leaflet (needs internet for tiles only; the app works without them).
 * `onPick` makes the map clickable: a click reports the coordinates and the pick marker moves there.
 */
export function LeafletMap({
  center,
  zoom = 13,
  markers = [],
  pick,
  onPick,
  height = 320,
}: {
  center: { lat: number; lng: number };
  zoom?: number;
  markers?: MapMarker[];
  pick?: { lat: number; lng: number } | null;
  onPick?: (lat: number, lng: number) => void;
  height?: number;
}) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const layer = useRef<L.LayerGroup | null>(null);
  const pickMarker = useRef<L.Marker | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;

  useEffect(() => {
    if (!el.current || map.current) return;
    const m = L.map(el.current).setView([center.lat, center.lng], zoom);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(m);
    layer.current = L.layerGroup().addTo(m);
    m.on('click', (e: L.LeafletMouseEvent) => onPickRef.current?.(Number(e.latlng.lat.toFixed(6)), Number(e.latlng.lng.toFixed(6))));
    map.current = m;
    return () => {
      m.remove();
      map.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    map.current?.setView([center.lat, center.lng], map.current.getZoom());
  }, [center.lat, center.lng]);

  useEffect(() => {
    const g = layer.current;
    if (!g) return;
    g.clearLayers();
    for (const mk of markers) {
      const m = L.marker([mk.lat, mk.lng]).addTo(g);
      if (mk.label) m.bindPopup(mk.label);
      if (mk.onClick) m.on('click', mk.onClick);
    }
  }, [markers]);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    if (pick) {
      if (pickMarker.current) pickMarker.current.setLatLng([pick.lat, pick.lng]);
      else pickMarker.current = L.marker([pick.lat, pick.lng]).addTo(m);
    } else if (pickMarker.current) {
      pickMarker.current.remove();
      pickMarker.current = null;
    }
  }, [pick]);

  return <div ref={el} className="map" style={{ height }} />;
}
