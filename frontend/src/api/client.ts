import type {
  Catalog, CaptureSummary, Category, HistoryEntry, ItemRequest, Mode, PcapRecord, Preview, Session, Tag, UploadResult,
} from './types';

/** An error answer from the backend: `code` is translated by the UI. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, public params: Record<string, unknown>, message: string) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', {}, 'Server unreachable');
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? 'INTERNAL_ERROR', data?.params ?? {}, data?.message ?? res.statusText);
  }
  return data as T;
}

export interface DraftBody {
  uploadId?: string | null;
  fileId?: string | null;
  categoryId: string | null;
  ports: number[];
  ip: string | null;
  startUs: number;
  endUs: number;
}

export interface CreateBody extends DraftBody {
  name: string;
  description: string;
  tagIds: string[];
  createdBy: string;
  resolution: 'NEW_NAME' | null;
}

export const api = {
  catalog: () => call<Catalog>('GET', '/api/catalog'),
  history: () => call<HistoryEntry[]>('GET', '/api/history'),
  fileSummary: (fileId: string) => call<CaptureSummary>('GET', `/api/files/${fileId}/summary`),

  /** Streams the file as the raw request body; reports upload progress 0..1. */
  upload(file: File, uploadedBy: string, onProgress: (f: number) => void): Promise<UploadResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const q = new URLSearchParams({ fileName: file.name, uploadedBy });
      xhr.open('POST', `/api/uploads?${q}`);
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
      xhr.onerror = () => reject(new ApiError(0, 'NETWORK', {}, 'Upload failed'));
      xhr.onload = () => {
        let data: any;
        try {
          data = JSON.parse(xhr.responseText);
        } catch {
          data = {};
        }
        if (xhr.status >= 200 && xhr.status < 300) resolve(data);
        else reject(new ApiError(xhr.status, data.code ?? 'UPLOAD_FAILED', data.params ?? {}, data.message ?? 'Upload failed'));
      };
      xhr.send(file);
    });
  },
  discardUpload: (id: string) => call<void>('DELETE', `/api/uploads/${id}`),

  preview: (d: DraftBody) => call<Preview>('POST', '/api/preview', d),
  createRecord: (b: CreateBody) => call<PcapRecord>('POST', '/api/records', b),
  updateRecord: (id: string, b: { name: string; description: string; startUs: number; endUs: number }) =>
    call<PcapRecord>('PUT', `/api/records/${id}`, b),
  deleteRecord: (id: string) => call<void>('DELETE', `/api/records/${id}`),
  downloadUrl: (id: string) => `/api/records/${id}/download`,

  createCategory: (name: string, mode: Mode) => call<Category>('POST', '/api/categories', { name, mode }),
  renameCategory: (id: string, name: string) => call<Category>('PUT', `/api/categories/${id}`, { name }),
  categoryUsage: (id: string) => call<{ records: number }>('GET', `/api/categories/${id}/usage`),
  deleteCategory: (id: string) => call<void>('DELETE', `/api/categories/${id}`),

  createTag: (name: string, categoryId: string | null) => call<Tag>('POST', '/api/tags', { name, categoryId }),
  renameTag: (id: string, name: string) => call<Tag>('PUT', `/api/tags/${id}`, { name }),
  deleteTag: (id: string) => call<void>('DELETE', `/api/tags/${id}`),

  sessions: () => call<Session[]>('GET', '/api/sessions'),
  start: (items: ItemRequest[], playlist: boolean, startedBy: string) =>
    call<Session>('POST', '/api/sessions', { items, playlist, startedBy }),
  stop: (id: string, by: string) => call<void>('POST', `/api/sessions/${id}/stop`, { by }),
  updateUpcoming: (id: string, currentSleepAfterMs: number | null, items: ItemRequest[]) =>
    call<Session>('PUT', `/api/sessions/${id}/upcoming`, { currentSleepAfterMs, items }),
};
