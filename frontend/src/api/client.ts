import type {
  Catalog, CaptureSummary, Category, HistoryEntry, ItemRequest, Mode, PcapRecord, Preview, SavedPlaylist, SavedPlaylistItem,
  Session, Tag, UploadResult, IcdSummary, IcdTree, TimeFixView, TimeFixSaveResult, MessageTimeFix,
  DecodeReport, TagRule, TagRulesView, TagJob, TagJobSummary, CreateTagJob, ArchiveFolder, ArchivePreview,
  AutoTagCapabilities, FileStatus,
} from './types';

/** An error answer from the backend: `code` is translated by the UI. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, public params: Record<string, unknown>, message: string) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  return send<T>(method, path, body === undefined ? undefined : JSON.stringify(body));
}

/** Sends an already-serialized JSON body (e.g. a file's text) as is. */
async function send<T>(method: string, path: string, jsonText?: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: jsonText === undefined ? undefined : { 'content-type': 'application/json' },
      body: jsonText,
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

/** The category must have the record's connection mode. */
export interface UpdateBody {
  name: string;
  description: string;
  startUs: number;
  endUs: number;
  categoryId: string;
  tagIds: string[];
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
  updateRecord: (id: string, body: UpdateBody) =>
    call<PcapRecord>('PUT', `/api/records/${id}`, body),
  deleteRecord: (id: string) => call<void>('DELETE', `/api/records/${id}`),
  downloadUrl: (id: string) => `/api/records/${id}/download`,

  createCategory: (name: string, mode: Mode) => call<Category>('POST', '/api/categories', { name, mode }),
  renameCategory: (id: string, name: string) => call<Category>('PUT', `/api/categories/${id}`, { name }),
  categoryUsage: (id: string) => call<{ records: number }>('GET', `/api/categories/${id}/usage`),
  /** `moveTo`: a category with the same mode that gets the records; without it they are deleted. */
  deleteCategory: (id: string, moveTo?: string) =>
    call<void>('DELETE', `/api/categories/${id}${moveTo ? `?moveTo=${encodeURIComponent(moveTo)}` : ''}`),

  createTag: (name: string) => call<Tag>('POST', '/api/tags', { name }),
  renameTag: (id: string, name: string) => call<Tag>('PUT', `/api/tags/${id}`, { name }),
  deleteTag: (id: string) => call<void>('DELETE', `/api/tags/${id}`),
  /** Replaces the tag with `intoId` on every record, then deletes it. */
  mergeTag: (id: string, intoId: string) => call<Tag>('POST', `/api/tags/${id}/merge`, { intoId }),
  retagRecords: (recordIds: string[], add: string[], remove: string[]) =>
    call<{ updated: number }>('POST', '/api/records/tags', { recordIds, add, remove }),

  createPlaylist: (name: string, items: SavedPlaylistItem[], by: string) =>
    call<SavedPlaylist>('POST', '/api/playlists', { name, items, by }),
  updatePlaylist: (id: string, name: string, items: SavedPlaylistItem[], by: string) =>
    call<SavedPlaylist>('PUT', `/api/playlists/${id}`, { name, items, by }),
  deletePlaylist: (id: string) => call<void>('DELETE', `/api/playlists/${id}`),

  sessions: () => call<Session[]>('GET', '/api/sessions'),
  /** `playlistName`: the saved playlist being played (recorded in History), if any. */
  start: (items: ItemRequest[], playlist: boolean, startedBy: string, playlistName: string | null = null) =>
    call<Session>('POST', '/api/sessions', { items, playlist, startedBy, playlistName }),
  /** "Play together": one replay per item, all starting at once; nothing starts if any target fails. */
  startGroup: (items: ItemRequest[], startedBy: string) =>
    call<Session[]>('POST', '/api/sessions/group', { items, startedBy }),
  stop: (id: string, by: string) => call<void>('POST', `/api/sessions/${id}/stop`, { by }),
  seek: (id: string, positionUs: number, by: string) =>
    call<void>('POST', `/api/sessions/${id}/seek`, { positionUs, by }),
  updateUpcoming: (id: string, currentSleepAfterMs: number | null, items: ItemRequest[]) =>
    call<Session>('PUT', `/api/sessions/${id}/upcoming`, { currentSleepAfterMs, items }),

  icds: () => call<IcdSummary[]>('GET', '/api/icds'),
  icdTree: (id: string) => call<IcdTree>('GET', `/api/icds/${encodeURIComponent(id)}/tree`),
  /** Uploads an ICD file's text (JSON ICD format); the same id replaces the uploaded one. */
  uploadIcd: (jsonText: string) => send<IcdSummary>('POST', '/api/icds', jsonText),
  deleteIcd: (id: string) => call<void>('DELETE', `/api/icds/${encodeURIComponent(id)}`),
  icdJsonUrl: (id: string) => `/api/icds/${encodeURIComponent(id)}/json`,
  setCategoryIcd: (categoryId: string, icdId: string | null) =>
    call<Category>('PUT', `/api/categories/${categoryId}/icd`, { icdId }),
  timeFix: (categoryId: string) => call<TimeFixView>('GET', `/api/categories/${categoryId}/timefix`),
  saveTimeFix: (categoryId: string, messages: MessageTimeFix[]) =>
    call<TimeFixSaveResult>('PUT', `/api/categories/${categoryId}/timefix`, { messages }),
  decodeRecord: (recordId: string, limit?: number, opcode?: string, icd?: string) => {
    const query = new URLSearchParams();
    if (limit !== undefined) query.set('limit', String(limit));
    if (opcode) query.set('opcode', opcode);
    if (icd) query.set('icd', icd);
    return call<DecodeReport>('GET', `/api/records/${recordId}/decode?${query}`);
  },

  /** Ids of records filtered by auto-tagging stats: a matched rule and/or "message type ≥ N". */
  searchRecords: (filter: { categoryId?: string; rule?: string; opcode?: number; minCount?: number }) => {
    const query = new URLSearchParams();
    if (filter.categoryId) query.set('categoryId', filter.categoryId);
    if (filter.rule) query.set('rule', filter.rule);
    if (filter.opcode !== undefined) query.set('opcode', String(filter.opcode));
    if (filter.minCount !== undefined) query.set('minCount', String(filter.minCount));
    return call<string[]>('GET', `/api/records/search?${query}`);
  },

  tagRules: (categoryId: string) => call<TagRulesView>('GET', `/api/categories/${categoryId}/tag-rules`),
  createTagRule: (categoryId: string, rule: TagRule) => call<TagRule>('POST', `/api/categories/${categoryId}/tag-rules`, rule),
  updateTagRule: (categoryId: string, rule: TagRule) =>
    call<TagRule>('PUT', `/api/categories/${categoryId}/tag-rules/${rule.id}`, rule),
  deleteTagRule: (categoryId: string, ruleId: string) => call<void>('DELETE', `/api/categories/${categoryId}/tag-rules/${ruleId}`),

  autoTagCapabilities: () => call<AutoTagCapabilities>('GET', '/api/auto-tag/capabilities'),
  archiveBrowse: (prefix: string) => call<ArchiveFolder>('GET', `/api/archive/browse?${new URLSearchParams({ prefix })}`),
  /** How many archive files a selection holds (and whether it is over the limit). */
  archivePreview: (request: CreateTagJob) => call<ArchivePreview>('POST', '/api/archive/preview', request),

  tagJobs: () => call<TagJobSummary[]>('GET', '/api/tag-jobs'),
  tagJob: (id: string, status?: FileStatus) =>
    call<TagJob>('GET', `/api/tag-jobs/${id}${status ? `?status=${status}` : ''}`),
  createTagJob: (request: CreateTagJob) => call<TagJob>('POST', '/api/tag-jobs', request),
  /** Streams one local file of an UPLOADING job as the raw body. */
  uploadTagJobFile: async (jobId: string, index: number, file: File): Promise<void> => {
    let response: Response;
    try {
      response = await fetch(`/api/tag-jobs/${jobId}/files/${index}`, { method: 'POST', body: file });
    } catch {
      throw new ApiError(0, 'NETWORK', {}, 'Upload failed');
    }
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new ApiError(response.status, data.code ?? 'UPLOAD_FAILED', data.params ?? {}, data.message ?? 'Upload failed');
    }
  },
  startTagJob: (id: string) => call<TagJob>('POST', `/api/tag-jobs/${id}/start`),
  cancelTagJob: (id: string) => call<TagJob>('POST', `/api/tag-jobs/${id}/cancel`),
};
