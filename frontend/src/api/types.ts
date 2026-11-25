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
  /** The ICD its messages follow; null when none is set. */
  icdId: string | null;
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
  /** Message statistics and matched rules from auto-tagging; null for records made by hand. */
  tagging: TaggingStats | null;
}

export interface MessageTypeCount {
  opcode: number;
  name: string;
  count: number;
}

/** Statistics of the whole file, decoded with the category's ICD by an auto-tagging run. */
export interface TaggingStats {
  icdId: string;
  totalMessages: number;
  counts: MessageTypeCount[];
  parseErrors: number;
  unknownOpcodes: number;
  matchedRules: string[];
  jobId: string;
  taggedAtMs: number;
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
  /** Not delivered: a TCP server replay had no connection when they were due (it keeps retrying). */
  missedMessages: number;
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

export type SessionState = 'PREPARING' | 'WAITING_FOR_GROUP' | 'CONNECTING' | 'WAITING_FOR_CONNECTION' | 'PLAYING' | 'SLEEPING';

/** Sessions started together ("Play together") share a group; `size` counts all of them. */
export interface SessionGroup {
  id: string;
  size: number;
  /** How many members are TCP clients: the group waits until all of them are connected. */
  tcpClients: number;
  connectedClients: number;
  /** False while waiting; true once every member started playing. */
  started: boolean;
}

/** The global limit on running replays (all users together). */
export interface Usage {
  active: number;
  max: number;
}

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
  group: SessionGroup | null;
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
  /** Not delivered for want of a connection (TCP server replays that keep trying); 0 or absent otherwise. */
  missedMessages?: number;
  totalMessages: number;
  bytesSent: number;
  result: 'COMPLETED' | 'STOPPED' | 'FAILED';
  stoppedBy: string | null;
  failureCode: string | null;
  failureParams: Record<string, unknown> | null;
  /** `name` is the saved playlist's name; null for an unsaved draft (and runs from before names were recorded). */
  playlist: { sessionId: string; index: number; total: number; name?: string | null } | null;
}

/** One item to play (single replay = one item). */
export interface ItemRequest {
  recordId: string;
  host: string | null;
  port: number;
  sleepAfterMs: number;
}

// ------------------------------------------------------------------ ICDs and time fixing

export type TimeKind = 'REGULAR_SECONDS' | 'REGULAR_MILLIS' | 'LTIME' | 'GPS';
export const TIME_KINDS: TimeKind[] = ['REGULAR_SECONDS', 'REGULAR_MILLIS', 'LTIME', 'GPS'];

export interface IcdSummary {
  id: string;
  name: string;
  /** JAVA ICDs are read-only. */
  source: 'JAVA' | 'JSON';
  messageCount: number;
  /** Ids of the categories using it. */
  usedBy: string[];
}

export type TreeNodeKind = 'HEADER' | 'STRUCT' | 'PRIMITIVE' | 'ENUM' | 'STRING' | 'BITFIELD' | 'BIT' | 'PADDING';

export interface IcdTreeNode {
  name: string;
  /** Array elements are written [*], e.g. targets[*].seenGps. */
  path: string;
  kind: TreeNodeKind;
  type: string;
  storageType: string | null;
  bitWidth: number;
  /** Bytes of one element; null when it depends on a count. */
  size: number | null;
  /** Array length (a number or the count field), or null. */
  array: string | null;
  /** Can hold a time. */
  numeric: boolean;
  inArray: boolean;
  children: IcdTreeNode[];
  /** For an enum: its values and names. */
  enumValues: { value: number; name: string }[];
}

export interface IcdTreeMessage {
  opcode: number;
  name: string;
  size: number | null;
  /** The header first. */
  fields: IcdTreeNode[];
}

export interface IcdTree {
  id: string;
  name: string;
  byteOrder: 'BIG_ENDIAN' | 'LITTLE_ENDIAN';
  messages: IcdTreeMessage[];
}

export interface TimeFieldRef {
  path: string;
  kind: TimeKind;
}

export interface MessageTimeFix {
  opcode: number;
  base: TimeFieldRef | null;
  fields: TimeFieldRef[];
}

export interface TimeFixProfile {
  categoryId: string;
  icdId: string;
  updatedAtMs: number;
  messages: MessageTimeFix[];
}

export interface TimeFixView {
  categoryId: string;
  /** The category's ICD; null when none is set. */
  icdId: string | null;
  profile: TimeFixProfile | null;
  tree: IcdTree | null;
  warnings: Problem[];
}

export interface TimeFixSaveResult {
  profile: TimeFixProfile;
  warnings: Problem[];
}

/** Debug output of GET /api/records/{id}/decode. */
export interface DecodeReport {
  icdId: string;
  scriptSteps: number;
  stats: { messages: number; problems: Record<string, number>; skippedBytes: number };
  counts: { opcode: number; name: string; count: number }[];
  messages: {
    opcode: number;
    name: string;
    flow: string;
    captureTsUs: number;
    firstStep: number;
    lastStep: number;
    length: number;
    trailingBytes: number;
    fields: Record<string, unknown>;
    locations: Record<string, { step: number; offset: number; length: number }[]>;
  }[];
}

// ------------------------------------------------------------------ auto-tagging

export type Operator = 'EQ' | 'NE' | 'GT' | 'GE' | 'LT' | 'LE' | 'BETWEEN' | 'IN' | 'NOT_IN' | 'CONTAINS' | 'STARTS_WITH';
export type ValueType = 'INTEGER' | 'FLOAT' | 'ENUM' | 'BOOL' | 'STRING';

/** One value of a predicate: number (integers, floats), name + number (enum), bool, or text. */
export interface RuleValue {
  /** Sent as a string so 64-bit integers keep every digit. */
  number?: string | number | null;
  name?: string | null;
  bool?: boolean | null;
  text?: string | null;
}

export interface FieldPredicate {
  path: string;
  op: Operator;
  values: RuleValue[];
}

export interface RuleCondition {
  kind: 'COUNT' | 'FIELDS';
  opcode: number;
  minCount: number;
  predicates: FieldPredicate[];
}

export interface TagRule {
  id: string | null;
  name: string;
  conditions: RuleCondition[];
}

export interface TagRulesView {
  categoryId: string;
  icdId: string | null;
  rules: TagRule[];
  tree: IcdTree | null;
  warnings: Problem[];
}

export type SourceKind = 'LOCAL_FILES' | 'LOCAL_FOLDER' | 'ARCHIVE_FILES' | 'ARCHIVE_FOLDER';
export type JobStatus = 'PENDING' | 'UPLOADING' | 'RUNNING' | 'DONE' | 'CANCELLED' | 'FAILED';
export type FileStatus = 'MATCHED_SAVED' | 'MATCHED_EXISTING_UPDATED' | 'MATCHED' | 'NOT_MATCHED' | 'NOT_A_PCAP' | 'FAILED';
export const FILE_STATUSES: FileStatus[] = ['MATCHED_SAVED', 'MATCHED_EXISTING_UPDATED', 'MATCHED', 'NOT_MATCHED', 'NOT_A_PCAP', 'FAILED'];

export interface TagJobSource {
  kind: SourceKind;
  folder: string | null;
  recursive: boolean;
  fromUs: number | null;
  toUs: number | null;
}

export interface JobInput {
  name: string;
  path: string;
  size: number;
}

export interface FileResult {
  index: number;
  name: string;
  path: string;
  size: number;
  gzipped: boolean;
  status: FileStatus;
  totalMessages: number;
  counts: MessageTypeCount[];
  parseErrors: number;
  unknownOpcodes: number;
  matchedRules: string[];
  recordIds: string[];
  error: string | null;
}

export interface TagJob {
  id: string;
  categoryId: string;
  categoryName: string;
  icdId: string;
  source: TagJobSource;
  rules: TagRule[];
  evaluateOnly: boolean;
  createdBy: string;
  status: JobStatus;
  failureReason: string | null;
  filesDone: number;
  bytesTotal: number;
  bytesDone: number;
  createdAtMs: number;
  startedAtMs: number;
  endedAtMs: number;
  inputs: JobInput[];
  results: FileResult[];
}

export interface TagJobSummary {
  id: string;
  categoryId: string;
  categoryName: string;
  source: TagJobSource;
  ruleNames: string[];
  evaluateOnly: boolean;
  createdBy: string;
  status: JobStatus;
  failureReason: string | null;
  filesTotal: number;
  filesDone: number;
  bytesTotal: number;
  bytesDone: number;
  createdAtMs: number;
  startedAtMs: number;
  endedAtMs: number;
  statusCounts: Partial<Record<FileStatus, number>>;
}

export interface CreateTagJob {
  categoryId: string;
  ruleIds: string[];
  evaluateOnly: boolean;
  kind: SourceKind;
  files?: JobInput[];
  keys?: string[];
  folder?: string | null;
  recursive?: boolean;
  fromUs?: number | null;
  toUs?: number | null;
  createdBy: string;
}

export interface ArchiveObject {
  key: string;
  size: number;
  lastModifiedUs: number;
}

export interface ArchiveFolder {
  prefix: string;
  folders: string[];
  files: ArchiveObject[];
}

export interface ArchivePreview {
  count: number;
  totalBytes: number;
  maxFiles: number;
  overLimit: boolean;
  sample: ArchiveObject[];
}

export interface AutoTagCapabilities {
  archiveConfigured: boolean;
  maxFilesPerRun: number;
}
