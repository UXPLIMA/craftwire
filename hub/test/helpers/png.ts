import { crc32, deflateSync } from "node:zlib";

/**
 * A tiny PNG encoder for tests: filter 0 rows, one IDAT. `depth` below 8 packs palette indices (colour type 3)
 * most significant bits first, as PNG does.
 */
export function encodePng(width: number, height: number, colorType: 2 | 6 | 3, pixels: number[], palette?: number[][], trns?: number[], depth = 8): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = depth; ihdr[9] = colorType;
  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : 1;
  const stride = Math.ceil((width * channels * depth) / 8);
  const raw = Buffer.alloc(height * (1 + stride));
  for (let y = 0; y < height; y++) {
    for (let i = 0; i < width * channels; i++) {
      const v = pixels[y * width * channels + i]!;
      if (depth === 8) raw[y * (1 + stride) + 1 + i] = v;
      else {
        const bit = i * depth, at = y * (1 + stride) + 1 + (bit >> 3);
        raw[at] = raw[at]! | (v << (8 - depth - (bit & 7)));
      }
    }
  }
  const parts = [Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr)];
  if (palette) parts.push(chunk("PLTE", Buffer.from(palette.flat())));
  if (trns) parts.push(chunk("tRNS", Buffer.from(trns)));
  parts.push(chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0)));
  return Buffer.concat(parts);
}
