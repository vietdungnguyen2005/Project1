import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const size = 32;
const xorBytes = size * size * 4;
const andMaskBytes = size * 4;
const dibSize = 40 + xorBytes + andMaskBytes;
const icoSize = 6 + 16 + dibSize;
const buffer = Buffer.alloc(icoSize);

let offset = 0;
buffer.writeUInt16LE(0, offset); offset += 2;
buffer.writeUInt16LE(1, offset); offset += 2;
buffer.writeUInt16LE(1, offset); offset += 2;
buffer.writeUInt8(size, offset++);
buffer.writeUInt8(size, offset++);
buffer.writeUInt8(0, offset++);
buffer.writeUInt8(0, offset++);
buffer.writeUInt16LE(1, offset); offset += 2;
buffer.writeUInt16LE(32, offset); offset += 2;
buffer.writeUInt32LE(dibSize, offset); offset += 4;
buffer.writeUInt32LE(22, offset); offset += 4;

buffer.writeUInt32LE(40, offset); offset += 4;
buffer.writeInt32LE(size, offset); offset += 4;
buffer.writeInt32LE(size * 2, offset); offset += 4;
buffer.writeUInt16LE(1, offset); offset += 2;
buffer.writeUInt16LE(32, offset); offset += 2;
buffer.writeUInt32LE(0, offset); offset += 4;
buffer.writeUInt32LE(xorBytes + andMaskBytes, offset); offset += 4;
buffer.writeInt32LE(2835, offset); offset += 4;
buffer.writeInt32LE(2835, offset); offset += 4;
buffer.writeUInt32LE(0, offset); offset += 4;
buffer.writeUInt32LE(0, offset); offset += 4;

for (let y = size - 1; y >= 0; y -= 1) {
  for (let x = 0; x < size; x += 1) {
    const isBorder = x < 3 || y < 3 || x >= size - 3 || y >= size - 3;
    const inVLeft = x >= 7 && x <= 11 && y >= 8 && y <= 17;
    const inVRight = x >= 20 && x <= 24 && y >= 8 && y <= 17;
    const inVPoint = x >= 13 && x <= 18 && y >= 17 && y <= 24;
    const isMark = inVLeft || inVRight || inVPoint;

    const color = isMark
      ? [195, 211, 41, 255]
      : isBorder
        ? [24, 19, 16, 255]
        : [24, 19, 16, 255];

    buffer.writeUInt8(color[0], offset++);
    buffer.writeUInt8(color[1], offset++);
    buffer.writeUInt8(color[2], offset++);
    buffer.writeUInt8(color[3], offset++);
  }
}

const outputPath = resolve("public/favicon.ico");
mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, buffer);
console.log(`Generated ${outputPath}`);
