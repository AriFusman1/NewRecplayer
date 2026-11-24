import type {
  Catalog, CaptureSummary, Category, HistoryEntry, ItemRequest, Mode, PcapRecord, Preview, SavedPlaylist, SavedPlaylistItem,
  Session, Tag, UploadResult,
} from './types';

/** An error answer from the backend: `code` is translated by the UI. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, public params: Record<string, unknown>, message: string) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: body === undefined ? undefined : { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', {}, 'Server unreachable');
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    throw new ApiError(response.status, data?.code ?? 'INTERNAL_ERROR', data?.params ?? {}, data?.message ?? response.statusText);
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
  upload(file: File, uploadedBy: string, onProgress: (fraction: number) => void): Promise<UploadResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const query = new URLSearchParams({ fileName: file.name, uploadedBy });
      xhr.open('POST', `/api/uploads?${query}`);
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

  preview: (draft: DraftBody) => call<Preview>('POST', '/api/preview', draft),
  createRecord: (body: CreateBody) => call<PcapRecord>('POST', '/api/records', body),
  updateRecord: (id: string, body: { name: string; description: string; startUs: number; endUs: number }) =>
    call<PcapRecord>('PUT', `/api/records/${id}`, body),
  deleteRecord: (id: string) => call<void>('DELETE', `/api/records/${id}`),
  downloadUrl: (id: string) => `/api/records/${id}/download`,

  createCategory: (name: string, mode: Mode) => call<Category>('POST', '/api/categories', { name, mode }),
  renameCategory: (id: string, name: string) => call<Category>('PUT', `/api/categories/${id}`, { name }),
  categoryUsage: (id: string) => call<{ records: number }>('GET', `/api/categories/${id}/usage`),
  deleteCategory: (id: string) => call<void>('DELETE', `/api/categories/${id}`),

  createTag: (name: string, categoryId: string | null) => call<Tag>('POST', '/api/tags', { name, categoryId }),
  renameTag: (id: string, name: string) => call<Tag>('PUT', `/api/tags/${id}`, { name }),
  deleteTag: (id: string) => call<void>('DELETE', `/api/tags/${id}`),

  createPlaylist: (name: string, items: SavedPlaylistItem[], by: string) =>
    call<SavedPlaylist>('POST', '/api/playlists', { name, items, by }),
  updatePlaylist: (id: string, name: string, items: SavedPlaylistItem[], by: string) =>
    call<SavedPlaylist>('PUT', `/api/playlists/${id}`, { name, items, by }),
  deletePlaylist: (id: string) => call<void>('DELETE', `/api/playlists/${id}`),

  sessions: () => call<Session[]>('GET', '/api/sessions'),
  start: (items: ItemRequest[], playlist: boolean, startedBy: string) =>
    call<Session>('POST', '/api/sessions', { items, playlist, startedBy }),
  /** "Play together": one replay per item, all starting at once; nothing starts if any target fails. */
  startGroup: (items: ItemRequest[], startedBy: string) =>
    call<Session[]>('POST', '/api/sessions/group', { items, startedBy }),
  stop: (id: string, by: string) => call<void>('POST', `/api/sessions/${id}/stop`, { by }),
  seek: (id: string, positionUs: number, by: string) =>
    call<void>('POST', `/api/sessions/${id}/seek`, { positionUs, by }),
  updateUpcoming: (id: string, currentSleepAfterMs: number | null, items: ItemRequest[]) =>
    call<Session>('PUT', `/api/sessions/${id}/upcoming`, { currentSleepAfterMs, items }),
};
