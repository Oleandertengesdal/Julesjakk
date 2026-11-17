// Copies the single-threaded Stockfish WASM build into public/ so it is served as a static file.
// The single-threaded "lite" build needs no SharedArrayBuffer, so no COOP/COEP headers are required.
import { cpSync, existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const source = join('node_modules', 'stockfish', 'bin');
const target = join('public', 'stockfish');
if (!existsSync(source)) {
  console.warn('stockfish package not installed yet; skipping engine copy');
  process.exit(0);
}
mkdirSync(target, { recursive: true });
const files = readdirSync(source).filter((f) => /-lite(-single)?\.(js|wasm)$/.test(f));
for (const file of files) {
  cpSync(join(source, file), join(target, file));
}
console.log(`Copied ${files.length} Stockfish files to ${target}`);
