import { useRef, useState } from 'react';
import { putToStorage } from '../api/endpoints';
import type { PhotoUploadSlot } from '../api/types';
import { Icon } from './Icon';
import { ProblemView, Spinner, useAction } from './ui';

const TYPES = ['image/jpeg', 'image/png', 'image/webp'];

/**
 * Presigned upload: ask the API for a slot, PUT the bytes straight to object storage, then confirm (the
 * server copies and validates the file). Accepts several files (uploaded one after another) when `multiple`.
 */
export function PhotoUploader({
  label,
  hint,
  maxBytes,
  multiple,
  disabled,
  createSlot,
  confirm,
  onDone,
}: {
  label: string;
  hint?: string;
  maxBytes: number;
  multiple?: boolean;
  disabled?: boolean;
  createSlot: (contentType: string, contentLength: number) => Promise<PhotoUploadSlot>;
  confirm: (slot: PhotoUploadSlot) => Promise<unknown>;
  onDone: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const act = useAction();

  const upload = async (files: File[]) => {
    await act.run(async () => {
      try {
        for (let i = 0; i < files.length; i++) {
          const file = files[i];
          setProgress(files.length > 1 ? `Uploading ${i + 1} of ${files.length}…` : 'Uploading…');
          if (!TYPES.includes(file.type)) throw new Error(`${file.name}: use a JPEG, PNG or WebP image.`);
          if (file.size > maxBytes) throw new Error(`${file.name} is larger than ${Math.round(maxBytes / 1024 / 1024)} MB.`);
          const slot = await createSlot(file.type, file.size);
          await putToStorage(slot, file);
          await confirm(slot);
          onDone();
        }
      } finally {
        setProgress(null);
        if (input.current) input.current.value = '';
      }
    });
  };

  const pick = (list: FileList | null) => {
    const files = Array.from(list ?? []);
    if (files.length) void upload(multiple ? files : files.slice(0, 1));
  };

  return (
    <div className="stack-sm">
      <label
        className={`dropzone ${over ? 'over' : ''}`}
        onDragOver={(e) => {
          e.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setOver(false);
          if (!disabled && !act.busy) pick(e.dataTransfer.files);
        }}
        aria-disabled={disabled || act.busy}
      >
        <input ref={input} type="file" accept={TYPES.join(',')} multiple={multiple} disabled={disabled || act.busy} onChange={(e) => pick(e.target.files)} />
        {progress ? <Spinner /> : <Icon name="camera" size={28} />}
        <strong>{progress ?? label}</strong>
        {!progress && <span className="small">{hint ?? 'Drag and drop, or click to choose. JPEG, PNG or WebP.'}</span>}
      </label>
      <ProblemView error={act.error} compact />
    </div>
  );
}
