// Mirrors the backend JSON. All times are epoch microseconds unless named *Ms.

export type Mode = 'TCP_SERVER' | 'UDP_SERVER' | 'TCP_CLIENT';
export const MODES: Mode[] = ['TCP_SERVER', 'TCP_CLIENT', 'UDP_SERVER'];

export interface Problem {
  code: string;
  params: Record<string, unknown>;
}

export interface Category {
  id: string;
  name: string;
  mode: Mode;
  createdAtMs: number;
}

export interface Tag {
  id: string;
  name: string;
}

export interface RecordStats {
  protocol: 'TCP' | 'UDP';
  messageCount: number;
  bytes: number;
  durationUs: number;
  connections: number;
  firstMessageUs: number;
  lastMessageUs: number;
  warnings: Problem[];
  binUs: number;
  histogram: number[];
}

export interface PcapRecord {
  id: string;
  fileId: string;
  name: string;
  description: string;
  categoryId: string;
  tagIds: string[];
  ports: number[];
  ip: string | null;
  startUs: number;
  endUs: number;
  stats: RecordStats;
  createdBy: string;
  createdAtMs: number;
  updatedAtMs: number;
}

export interface FileView {
  id: string;
  sha256: string;
  originalName: string;
  size: number;
  format: string;
  uploadedBy: string;
  uploadedAtMs: number;
  startUs: number;
  endUs: number;
}

/** A saved playlist item has the same shape as an item to play. */
export type SavedPlaylistItem = ItemRequest;

/** A named playlist stored on the server, shared by everyone. */
export interface SavedPlaylist {
  id: string;
  name: string;
  items: SavedPlaylistItem[];
  createdBy: string;
  createdAtMs: number;
  updatedBy: string;
  updatedAtMs: number;
}

export interface Catalog {
  categories: Category[];
  tags: Tag[];
  records: PcapRecord[];
  files: FileView[];
  playlists: SavedPlaylist[];
}

export interface Endpoint {
  protocol: 'TCP' | 'UDP';
  role: 'dst' | 'src';
  port: number;
  packets: number;
  bytes: number;
  ips: string[];
  /** First timeline bucket with packets; absent on summaries stored before timelines existed. */
  binFrom?: number;
  /** Payload packets per bucket from binFrom. */
  counts?: number[];
}

export interface CaptureSummary {
  format: string;
  frames: number;
  startUs: number;
  endUs: number;
  tcpPackets: number;
  udpPackets: number;
  endpoints: Endpoint[];
  warnings: Problem[];
  /** Width of a timeline bucket; absent/0 on older summaries. */
  binUs?: number;
  /** TCP/UDP packets per bucket from startUs. */
  timeline?: number[];
}

export interface SameFile {
  fileId: string;
  originalName: string;
  records: string[];
}

export interface UploadResult {
  uploadId: string;
  fileName: string;
  size: number;
  sha256: string;
  summary: CaptureSummary;
  sameFile: SameFile | null;
}

export interface Preview {
  stats: RecordStats | null;
  errors: Problem[];
}

export interface SessionItem {
  itemId: string;
  recordId: string;
  recordName: string;
  categoryName: string;
  mode: Mode;
  host: string | null;
  port: number;
  sleepAfterMs: number;
  status: 'PENDING' | 'PLAYING' | 'COMPLETED' | 'FAILED' | 'STOPPED';
}

export interface SessionProgress {
  rangeStartUs: number;
  durationUs: number;
  positionUs: number;
  sentMessages: number;
  totalMessages: number;
  sentBytes: number;
  binUs: number;
  histogram: number[];
}

export interface LogLine {
  tsMs: number;
  level: 'info' | 'warning' | 'error';
  code: string;
  params: Record<string, unknown>;
}

export type SessionState = 'PREPARING' | 'CONNECTING' | 'WAITING_FOR_CONNECTION' | 'PLAYING' | 'SLEEPING';

export interface Session {
  id: string;
  playlist: boolean;
  startedBy: string;
  startedAtMs: number;
  version: number;
  state: SessionState;
  currentIndex: number;
  items: SessionItem[];
  progress: SessionProgress | null;
  sleepRemainingMs: number;
  log: LogLine[];
}

export interface HistoryEntry {
  id: string;
  recordId: string;
  recordName: string;
  categoryName: string;
  mode: Mode;
  target: string;
  startedBy: string;
  startedAtMs: number;
  endedAtMs: number;
  messagesSent: number;
  totalMessages: number;
  bytesSent: number;
  result: 'COMPLETED' | 'STOPPED' | 'FAILED';
  stoppedBy: string | null;
  failureCode: string | null;
  failureParams: Record<string, unknown> | null;
  playlist: { sessionId: string; index: number; total: number } | null;
}

/** One item to play (single replay = one item). */
export interface ItemRequest {
  recordId: string;
  host: string | null;
  port: number;
  sleepAfterMs: number;
}
