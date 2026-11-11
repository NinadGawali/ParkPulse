import { useRef, useState } from 'react';
import { putToStorage } from '../api/endpoints';
import type { PhotoUploadSlot } from '../api/types';
import { ProblemView, useAction } from './ui';

const TYPES = ['image/jpeg', 'image/png', 'image/webp'];

/** Presigned upload in three visible steps: request slot, PUT bytes to object storage, confirm. */
export function PhotoUploader({
  label,
  maxBytes,
  createSlot,
  confirm,
  onDone,
}: {
  label: string;
  maxBytes: number;
  createSlot: (contentType: string, contentLength: number) => Promise<PhotoUploadSlot>;
  confirm: (slot: PhotoUploadSlot) => Promise<unknown>;
  onDone: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [steps, setSteps] = useState<string[]>([]);
  const act = useAction();

  const upload = async (file: File) => {
    setSteps([]);
    const log = (s: string) => setSteps((cur) => [...cur, s]);
    await act.run(async () => {
      if (!TYPES.includes(file.type)) throw new Error(`Unsupported type ${file.type || 'unknown'}; use jpeg, png or webp.`);
      if (file.size > maxBytes) throw new Error(`File is ${file.size} bytes; the limit is ${maxBytes}.`);
      const slot = await createSlot(file.type, file.size);
      log(`1. Upload slot created (${slot.uploadId ?? slot.photoId}), expires ${slot.expiresAt}`);
      const put = await putToStorage(slot, file);
      log(`2. PUT ${file.size} bytes to object storage via ${put.url.split('?')[0]} -> ${put.status}`);
      await confirm(slot);
      log('3. Confirmed: the server copied it to a public key and checked type, size and signature.');
      onDone();
    });
    if (input.current) input.current.value = '';
  };

  return (
    <div className="uploader">
      <label className="field-label">{label}</label>
      <input
        ref={input}
        type="file"
        accept={TYPES.join(',')}
        disabled={act.busy}
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) void upload(f);
        }}
      />
      {steps.length > 0 && (
        <ol className="steps">
          {steps.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ol>
      )}
      <ProblemView error={act.error} />
    </div>
  );
}
