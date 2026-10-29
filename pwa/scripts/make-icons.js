'use strict';
// Renders the Android launcher icon (app_icon.xml) to the PNG sizes a PWA needs. No dependencies.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT = path.join(__dirname, '..', 'public', 'icons');
const BG = [0x11, 0x18, 0x27];
const ACCENT = [0x58, 0xd6, 0xc7];
const SAMPLES = 4;

function inTriangle(x, y) {
  // Play triangle from the vector icon: (20,16) (33,24) (20,32) in a 48 unit viewport.
  const pts = [[20, 16], [33, 24], [20, 32]];
  let sign = 0;
  for (let i = 0; i < 3; i++) {
    const [ax, ay] = pts[i];
    const [bx, by] = pts[(i + 1) % 3];
    const cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
    if (cross === 0) continue;
    if (sign === 0) sign = Math.sign(cross);
    else if (Math.sign(cross) !== sign) return false;
  }
  return true;
}

function colorAt(u, v) {
  const x = u * 48;
  const y = v * 48;
  if (x >= 8 && x <= 40 && y >= 10 && y <= 38) return inTriangle(x, y) ? BG : ACCENT;
  return BG;
}

function render(size) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let py = 0; py < size; py++) {
    raw[py * (size * 4 + 1)] = 0;
    for (let px = 0; px < size; px++) {
      let r = 0; let g = 0; let b = 0;
      for (let sy = 0; sy < SAMPLES; sy++) {
        for (let sx = 0; sx < SAMPLES; sx++) {
          const c = colorAt((px + (sx + 0.5) / SAMPLES) / size, (py + (sy + 0.5) / SAMPLES) / size);
          r += c[0]; g += c[1]; b += c[2];
        }
      }
      const n = SAMPLES * SAMPLES;
      const o = py * (size * 4 + 1) + 1 + px * 4;
      raw[o] = Math.round(r / n); raw[o + 1] = Math.round(g / n); raw[o + 2] = Math.round(b / n); raw[o + 3] = 255;
    }
  }
  return raw;
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

function png(size) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; header[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(render(size), { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

fs.mkdirSync(OUT, { recursive: true });
const targets = {
  'icon-512.png': 512, 'icon-192.png': 192, 'icon-maskable-512.png': 512,
  'apple-touch-icon.png': 180, 'apple-touch-icon-167.png': 167,
  'apple-touch-icon-152.png': 152, 'favicon-32.png': 32,
};
for (const [name, size] of Object.entries(targets)) {
  fs.writeFileSync(path.join(OUT, name), png(size));
  console.log('wrote', name);
}
fs.writeFileSync(path.join(OUT, 'icon.svg'),
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path fill="#111827" d="M0 0h48v48H0z"/>'
  + '<path fill="#58D6C7" d="M8 10h32v28H8z"/><path fill="#111827" d="M20 16l13 8-13 8z"/></svg>\n');
