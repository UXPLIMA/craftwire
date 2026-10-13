import { inflateSync } from "node:zlib";

/**
 * PNG to RGBA for block textures: colour types 0, 2, 3, 4, 6, not interlaced; bit depth 8, or 1/2/4 for palette and
 * grey images (the game ships some 4-bit palette textures).
 */
export function decodePng(buf: Buffer): { width: number; height: number; rgba: Uint8Array } {
  if (buf.length < 8 || buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not a PNG file");
  let p = 8, width = 0, height = 0, type = 0, depth = 0, interlace = 0;
  let palette: Buffer | undefined, trns: Buffer | undefined;
  const idat: Buffer[] = [];
  while (p < buf.length) {
    const len = buf.readUInt32BE(p), kind = buf.toString("ascii", p + 4, p + 8), data = buf.subarray(p + 8, p + 8 + len);
    if (kind === "IHDR") { width = data.readUInt32BE(0); height = data.readUInt32BE(4); depth = data[8]!; type = data[9]!; interlace = data[12]!; }
    else if (kind === "PLTE") palette = data;
    else if (kind === "tRNS") trns = data;
    else if (kind === "IDAT") idat.push(data);
    else if (kind === "IEND") break;
    p += 12 + len;
  }
  const channels = ({ 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 } as Record<number, number>)[type];
  if (channels === undefined) throw new Error(`unsupported PNG colour type ${type}`);
  const lowDepth = (type === 0 || type === 3) && (depth === 1 || depth === 2 || depth === 4);
  if ((depth !== 8 && !lowDepth) || interlace !== 0) throw new Error(`unsupported PNG (bit depth ${depth}, interlace ${interlace})`);
  const raw = inflateSync(Buffer.concat(idat));
  // Filters work on bytes; a pixel is at least one byte to the filter.
  const stride = Math.ceil((width * channels * depth) / 8);
  const bpp = Math.max(1, (channels * depth) >> 3);
  const rows = new Uint8Array(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]!;
    for (let i = 0; i < stride; i++) {
      const x = raw[y * (stride + 1) + 1 + i]!;
      const a = i >= bpp ? rows[y * stride + i - bpp]! : 0;
      const b = y > 0 ? rows[(y - 1) * stride + i]! : 0;
      const c = y > 0 && i >= bpp ? rows[(y - 1) * stride + i - bpp]! : 0;
      let v = x;
      if (filter === 1) v = x + a;
      else if (filter === 2) v = x + b;
      else if (filter === 3) v = x + ((a + b) >> 1);
      else if (filter === 4) {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      }
      rows[y * stride + i] = v & 0xff;
    }
  }
  // One sample per channel: low depths unpack most significant bits first; grey scales up to 0-255.
  const sample = (y: number, i: number): number => {
    if (depth === 8) return rows[y * stride + i]!;
    const bit = i * depth;
    const v = (rows[y * stride + (bit >> 3)]! >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
    return type === 0 ? Math.round((v * 255) / ((1 << depth) - 1)) : v;
  };
  const rgba = new Uint8Array(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const o = (y * width + x) * 4, s = x * channels;
      if (type === 6) for (let k = 0; k < 4; k++) rgba[o + k] = sample(y, s + k);
      else if (type === 2) { for (let k = 0; k < 3; k++) rgba[o + k] = sample(y, s + k); rgba[o + 3] = 255; }
      else if (type === 0) { rgba.fill(sample(y, s), o, o + 3); rgba[o + 3] = 255; }
      else if (type === 4) { rgba.fill(sample(y, s), o, o + 3); rgba[o + 3] = sample(y, s + 1); }
      else {
        const k = sample(y, s);
        rgba[o] = palette![k * 3]!; rgba[o + 1] = palette![k * 3 + 1]!; rgba[o + 2] = palette![k * 3 + 2]!;
        rgba[o + 3] = trns && k < trns.length ? trns[k]! : 255;
      }
    }
  }
  return { width, height, rgba };
}
