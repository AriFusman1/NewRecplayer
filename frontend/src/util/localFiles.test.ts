// Run with `npm test`.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { chosenFiles, filterFolder, folderName, totalSize } from './localFiles.js';

const US_PER_MS = 1000;

function file(path: string, size: number, lastModified: number): File {
  return { name: path.split('/').pop()!, size, lastModified, webkitRelativePath: path } as unknown as File;
}

const FILES = [
  file('caps/a.pcap', 10, 1000),
  file('caps/b.pcap.gz', 20, 2000),
  file('caps/empty.pcap', 0, 2000),
  file('caps/day2/c.pcap', 30, 3000),
];

test('folder name and sizes', () => {
  assert.equal(folderName(FILES), 'caps');
  assert.equal(totalSize(filterFolder(FILES, true, null, null)), 60);
  assert.deepEqual(chosenFiles(FILES).map((local) => local.path), ['a.pcap', 'b.pcap.gz', 'c.pcap'], 'empty files are skipped');
});

test('subfolders only when asked', () => {
  assert.deepEqual(filterFolder(FILES, false, null, null).map((local) => local.path), ['a.pcap', 'b.pcap.gz']);
  assert.deepEqual(filterFolder(FILES, true, null, null).map((local) => local.path), ['a.pcap', 'b.pcap.gz', 'day2/c.pcap']);
});

test('lastModified span is inclusive at both ends', () => {
  const paths = (from: number | null, to: number | null) => filterFolder(FILES, true, from, to).map((local) => local.path);

  assert.deepEqual(paths(2000 * US_PER_MS, 3000 * US_PER_MS), ['b.pcap.gz', 'day2/c.pcap']);
  assert.deepEqual(paths(1000 * US_PER_MS, 2000 * US_PER_MS), ['a.pcap', 'b.pcap.gz']);
  assert.deepEqual(paths(2000 * US_PER_MS + 1, null), ['day2/c.pcap']);
  assert.deepEqual(paths(null, 1000 * US_PER_MS - 1), []);
});
