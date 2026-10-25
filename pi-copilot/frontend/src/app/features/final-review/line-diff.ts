export interface DiffLine {
  type: 'same' | 'add' | 'del';
  text: string;
  oldNo: number | null;
  newNo: number | null;
}

export interface DiffHunk {
  header: string;
  lines: DiffLine[];
}

export interface FileDiff {
  hunks: DiffHunk[];
  added: number;
  removed: number;
}

const CONTEXT = 3;
/** Above this many line pairs the LCS table gets too big; show the file as replaced instead. */
const MAX_CELLS = 4_000_000;

/** Unified line diff (LCS) of two texts, grouped into hunks with a few lines of context. */
export function diffLines(oldText: string | null, newText: string | null): FileDiff {
  const a = splitLines(oldText);
  const b = splitLines(newText);
  const lines = a.length * b.length > MAX_CELLS ? replaceAll(a, b) : lcsDiff(a, b);
  return {
    hunks: toHunks(lines),
    added: lines.filter((l) => l.type === 'add').length,
    removed: lines.filter((l) => l.type === 'del').length,
  };
}

function splitLines(text: string | null): string[] {
  if (!text) return [];
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

function lcsDiff(a: string[], b: string[]): DiffLine[] {
  const n = a.length;
  const m = b.length;
  // table[i][j] = LCS length of a[i..] and b[j..]
  const table: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i], oldNo: i + 1, newNo: j + 1 });
      i++;
      j++;
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      out.push({ type: 'del', text: a[i], oldNo: i + 1, newNo: null });
      i++;
    } else {
      out.push({ type: 'add', text: b[j], oldNo: null, newNo: j + 1 });
      j++;
    }
  }
  for (; i < n; i++) out.push({ type: 'del', text: a[i], oldNo: i + 1, newNo: null });
  for (; j < m; j++) out.push({ type: 'add', text: b[j], oldNo: null, newNo: j + 1 });
  return out;
}

function replaceAll(a: string[], b: string[]): DiffLine[] {
  return [
    ...a.map((text, i) => ({ type: 'del' as const, text, oldNo: i + 1, newNo: null })),
    ...b.map((text, i) => ({ type: 'add' as const, text, oldNo: null, newNo: i + 1 })),
  ];
}

function toHunks(lines: DiffLine[]): DiffHunk[] {
  const changed = lines.map((l, i) => (l.type === 'same' ? -1 : i)).filter((i) => i >= 0);
  if (changed.length === 0) return [];
  const hunks: DiffHunk[] = [];
  let start = Math.max(0, changed[0] - CONTEXT);
  let end = Math.min(lines.length - 1, changed[0] + CONTEXT);
  for (const idx of changed.slice(1)) {
    if (idx - CONTEXT <= end + 1) {
      end = Math.min(lines.length - 1, idx + CONTEXT);
    } else {
      hunks.push(hunk(lines, start, end));
      start = Math.max(0, idx - CONTEXT);
      end = Math.min(lines.length - 1, idx + CONTEXT);
    }
  }
  hunks.push(hunk(lines, start, end));
  return hunks;
}

function hunk(lines: DiffLine[], start: number, end: number): DiffHunk {
  const slice = lines.slice(start, end + 1);
  const oldStart = slice.find((l) => l.oldNo !== null)?.oldNo ?? 0;
  const newStart = slice.find((l) => l.newNo !== null)?.newNo ?? 0;
  const oldCount = slice.filter((l) => l.type !== 'add').length;
  const newCount = slice.filter((l) => l.type !== 'del').length;
  return { header: `@@ -${oldStart},${oldCount} +${newStart},${newCount} @@`, lines: slice };
}
