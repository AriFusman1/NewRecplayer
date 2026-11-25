// Choosing local files for auto-tagging. Folder filtering happens in the browser, so only the files
// that will be tagged are uploaded.

const US_PER_MS = 1000;

/** A local file and where it is inside the chosen folder. */
export interface LocalFile {
  file: File;
  /** "sub/x.pcap" relative to the chosen folder; just the name for chosen files. */
  path: string;
}

/** The chosen folder's name ("captures" for "captures/a/x.pcap"). */
export function folderName(files: File[]): string | null {
  const first = files.find((file) => file.webkitRelativePath);
  return first ? first.webkitRelativePath.split('/')[0] : null;
}

/**
 * The files of a folder pick that will be tagged: not empty, directly in the folder unless
 * `recursive`, and last modified inside the span (epoch µs, both ends inclusive; null = open).
 */
export function filterFolder(files: File[], recursive: boolean, fromUs: number | null, toUs: number | null): LocalFile[] {
  return files
    .filter((file) => file.size > 0)
    .map((file) => ({ file, parts: file.webkitRelativePath.split('/') }))
    .filter(({ parts }) => recursive || parts.length <= 2)
    .filter(({ file }) => fromUs === null || file.lastModified * US_PER_MS >= fromUs)
    .filter(({ file }) => toUs === null || file.lastModified * US_PER_MS <= toUs)
    .map(({ file, parts }) => ({ file, path: parts.slice(1).join('/') || file.name }));
}

export function chosenFiles(files: File[]): LocalFile[] {
  return files.filter((file) => file.size > 0).map((file) => ({ file, path: file.name }));
}

export function totalSize(files: LocalFile[]): number {
  return files.reduce((sum, { file }) => sum + file.size, 0);
}
