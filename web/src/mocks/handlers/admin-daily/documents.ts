/**
 * Файли розпорядження для мок-API (GET /api/documents/orders/{id}.{docx|pdf|png}). Справжні файли робить
 * сервіс notification (POI → LibreOffice → PDFBox); тут — чесні мінімальні аналоги того ж макета:
 * PNG малюється на canvas, PDF — сторінка A4 з цим зображенням, DOCX — таблиця WordprocessingML у ZIP.
 */
import type { OrderDto, OrderItemDto } from '@/api/types';
import { formatLongDate } from '@/lib/date';

const IN_TESTS = import.meta.env.MODE === 'test';
const encoder = new TextEncoder();

// ───────────────────────── Текст документа (спільний для всіх форматів) ─────────────────────────

export function documentHeading(order: OrderDto, parentNumber: number | null): string[] {
  const number = order.number !== null ? `№${order.number}` : '№ ___';
  const first =
    order.kind === 'AMENDMENT'
      ? `ДОПОВНЕННЯ ${number} до розпорядження №${parentNumber ?? '—'}`
      : `РОЗПОРЯДЖЕННЯ ${number}`;
  return [first, order.title, `На ${formatLongDate(order.date)} р.`, `${order.weekdayLabel} - ${order.parityLabel}`];
}

/** Клітинки рядка: група, пара, дисципліна й викладач за розкладом, причина, дисципліна й викладач заміни, ауд. */
export function rowCells(item: OrderItemDto): { cells: string[]; mergeLeft: string | null; mergeRight: string | null } {
  const cells = [
    item.groupCode,
    item.pairLabel,
    item.fromDiscipline ?? '',
    item.fromTeacher ?? '',
    item.reasonText ?? '',
    item.toDiscipline ?? '',
    item.toTeacher ?? '',
    item.roomText ?? '',
  ];
  const mergeLeft = item.rowType === 'ADDITIONAL' ? 'ДОДАТКОВО' : item.rowType === 'AS_SCHEDULED' ? 'За розкладом' : null;
  const mergeRight = item.rowType === 'CANCELLATION' ? 'Пари не буде' : null;
  return { cells, mergeLeft, mergeRight };
}

// ───────────────────────── PNG (canvas) ─────────────────────────

/** 1×1 білий PNG — замість малюнка у Vitest, де немає canvas. */
const BLANK_PNG = Uint8Array.from(
  atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII='),
  (c) => c.charCodeAt(0),
);

const PAGE = { width: 1240, height: 1754, margin: 110 } as const;
const COLUMNS = [0.085, 0.05, 0.12, 0.15, 0.1, 0.12, 0.15, 0.06];

function wrap(ctx: CanvasRenderingContext2D, text: string, width: number): string[] {
  const words = text.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let line = '';
  for (const word of words) {
    const next = line ? `${line} ${word}` : word;
    if (ctx.measureText(next).width > width && line) {
      lines.push(line);
      line = word;
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines.length > 0 ? lines : [''];
}

function drawOrder(order: OrderDto, parentNumber: number | null): HTMLCanvasElement {
  const canvas = document.createElement('canvas');
  canvas.width = PAGE.width;
  canvas.height = PAGE.height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas 2D недоступний');
  const ink = '#111111';
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, PAGE.width, PAGE.height);
  ctx.fillStyle = ink;
  ctx.strokeStyle = ink;
  ctx.textBaseline = 'top';
  const serif = '"Times New Roman", "Liberation Serif", Times, serif';
  const inner = PAGE.width - PAGE.margin * 2;

  let y = 90;
  documentHeading(order, parentNumber).forEach((line, index) => {
    ctx.font = `${index === 0 || index === 3 ? 'bold ' : ''}${index === 0 ? 34 : 28}px ${serif}`;
    ctx.textAlign = 'center';
    ctx.fillText(line, PAGE.width / 2, y);
    y += index === 0 ? 46 : 38;
  });
  y += 18;

  const xs = COLUMNS.reduce<number[]>((acc, share) => [...acc, (acc.at(-1) ?? PAGE.margin) + share * inner], [PAGE.margin]);
  const cellLeft = (index: number) => xs[index] ?? PAGE.margin;
  ctx.font = `24px ${serif}`;
  ctx.lineWidth = 1.5;

  const drawRow = (cells: Array<{ text: string; from: number; to: number; bold?: boolean; center?: boolean }>) => {
    const heights = cells.map((cell) => {
      ctx.font = `${cell.bold ? 'bold ' : ''}24px ${serif}`;
      return wrap(ctx, cell.text, cellLeft(cell.to) - cellLeft(cell.from) - 12).length * 28 + 12;
    });
    const height = Math.max(40, ...heights);
    for (const cell of cells) {
      const left = cellLeft(cell.from);
      const width = cellLeft(cell.to) - left;
      ctx.strokeRect(left, y, width, height);
      ctx.font = `${cell.bold ? 'bold ' : ''}24px ${serif}`;
      ctx.textAlign = cell.center ? 'center' : 'left';
      wrap(ctx, cell.text, width - 12).forEach((line, index) => {
        ctx.fillText(line, cell.center ? left + width / 2 : left + 6, y + 6 + index * 28);
      });
    }
    y += height;
  };

  drawRow([
    { text: 'Група', from: 0, to: 1, bold: true, center: true },
    { text: 'Пара', from: 1, to: 2, bold: true, center: true },
    { text: 'За розкладом', from: 2, to: 4, bold: true, center: true },
    { text: 'Причина заміни', from: 4, to: 5, bold: true, center: true },
    { text: 'По заміні', from: 5, to: 7, bold: true, center: true },
    { text: 'Ауд', from: 7, to: 8, bold: true, center: true },
  ]);
  for (const item of order.items) {
    const { cells, mergeLeft, mergeRight } = rowCells(item);
    drawRow([
      { text: cells[0] ?? '', from: 0, to: 1 },
      { text: cells[1] ?? '', from: 1, to: 2 },
      ...(mergeLeft
        ? [{ text: mergeLeft, from: 2, to: 4 }]
        : [
            { text: cells[2] ?? '', from: 2, to: 3 },
            { text: cells[3] ?? '', from: 3, to: 4 },
          ]),
      { text: cells[4] ?? '', from: 4, to: 5 },
      ...(mergeRight
        ? [{ text: mergeRight, from: 5, to: 7 }]
        : [
            { text: cells[5] ?? '', from: 5, to: 6 },
            { text: cells[6] ?? '', from: 6, to: 7 },
          ]),
      { text: cells[7] ?? '', from: 7, to: 8 },
    ]);
  }
  drawRow([{ text: 'ЗАМІНА  АУДИТОРІЙ', from: 0, to: 8, center: true }]);
  for (const item of order.roomChanges) {
    drawRow([
      { text: item.groupCode, from: 0, to: 1 },
      { text: item.pairLabel, from: 1, to: 2 },
      { text: '', from: 2, to: 7 },
      { text: item.roomText ?? '', from: 7, to: 8 },
    ]);
  }

  y += 36;
  ctx.textAlign = 'left';
  ctx.font = `bold 26px ${serif}`;
  for (const note of order.notes) {
    for (const line of wrap(ctx, note, inner - 40)) {
      ctx.fillText(line, PAGE.margin + 40, y);
      y += 34;
    }
  }
  y += 40;
  ctx.fillText(order.signatoryPosition, PAGE.margin + 40, y);
  ctx.textAlign = 'right';
  ctx.fillText(order.signatoryName, PAGE.width - PAGE.margin - 40, y);
  return canvas;
}

function canvasBytes(canvas: HTMLCanvasElement, type: 'image/png' | 'image/jpeg'): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          reject(new Error('Не вдалося намалювати розпорядження'));
          return;
        }
        void blob.arrayBuffer().then((buffer) => resolve(new Uint8Array(buffer)), reject);
      },
      type,
      0.92,
    );
  });
}

export async function orderPng(order: OrderDto, parentNumber: number | null): Promise<Uint8Array> {
  if (IN_TESTS) return BLANK_PNG;
  return canvasBytes(drawOrder(order, parentNumber), 'image/png');
}

// ───────────────────────── PDF (сторінка A4 із зображенням) ─────────────────────────

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

/** Мінімальний PDF 1.4: одна сторінка A4 з JPEG на весь аркуш (/DCTDecode). */
export function pdfWithJpeg(jpeg: Uint8Array, width: number, height: number): Uint8Array {
  const parts: Uint8Array[] = [];
  const offsets: number[] = [];
  let length = 0;
  const push = (part: Uint8Array | string) => {
    const bytes = typeof part === 'string' ? encoder.encode(part) : part;
    parts.push(bytes);
    length += bytes.length;
  };
  const object = (body: string) => {
    offsets.push(length);
    push(`${offsets.length} 0 obj\n${body}\nendobj\n`);
  };
  push('%PDF-1.4\n');
  object('<< /Type /Catalog /Pages 2 0 R >>');
  object('<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
  object('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.28 841.89] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>');
  offsets.push(length);
  push(
    `4 0 obj\n<< /Type /XObject /Subtype /Image /Width ${width} /Height ${height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`,
  );
  push(jpeg);
  push('\nendstream\nendobj\n');
  const content = 'q 595.28 0 0 841.89 0 0 cm /Im0 Do Q';
  object(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
  const xref = length;
  push(`xref\n0 ${offsets.length + 1}\n0000000000 65535 f \n`);
  for (const offset of offsets) push(`${String(offset).padStart(10, '0')} 00000 n \n`);
  push(`trailer\n<< /Size ${offsets.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
  return concat(parts);
}

export async function orderPdf(order: OrderDto, parentNumber: number | null): Promise<Uint8Array> {
  if (IN_TESTS) return pdfWithJpeg(new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), 1, 1);
  const jpeg = await canvasBytes(drawOrder(order, parentNumber), 'image/jpeg');
  return pdfWithJpeg(jpeg, PAGE.width, PAGE.height);
}

// ───────────────────────── DOCX (WordprocessingML у ZIP без стиснення) ─────────────────────────

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = (CRC_TABLE[(crc ^ byte) & 0xff] ?? 0) ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

/** ZIP «stored»: локальні заголовки, центральний каталог, кінцевий запис. Імена в UTF-8 (прапорець 0x0800). */
export function zipStored(files: ReadonlyArray<{ name: string; data: Uint8Array }>): Uint8Array {
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;
  for (const file of files) {
    const name = encoder.encode(file.name);
    const crc = crc32(file.data);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true);
    local.setUint16(8, 0, true);
    local.setUint16(10, 0, true);
    local.setUint16(12, 0x21, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, file.data.length, true);
    local.setUint32(22, file.data.length, true);
    local.setUint16(26, name.length, true);
    local.setUint16(28, 0, true);
    const localBytes = concat([new Uint8Array(local.buffer), name, file.data]);
    const central = new DataView(new ArrayBuffer(46));
    central.setUint32(0, 0x02014b50, true);
    central.setUint16(4, 20, true);
    central.setUint16(6, 20, true);
    central.setUint16(8, 0x0800, true);
    central.setUint16(10, 0, true);
    central.setUint16(12, 0, true);
    central.setUint16(14, 0x21, true);
    central.setUint32(16, crc, true);
    central.setUint32(20, file.data.length, true);
    central.setUint32(24, file.data.length, true);
    central.setUint16(28, name.length, true);
    central.setUint32(42, offset, true);
    centrals.push(concat([new Uint8Array(central.buffer), name]));
    locals.push(localBytes);
    offset += localBytes.length;
  }
  const directory = concat(centrals);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, directory.length, true);
  end.setUint32(16, offset, true);
  return concat([...locals, directory, new Uint8Array(end.buffer)]);
}

const escapeXml = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const RUN_FONT = '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/>';

function paragraph(text: string, options: { bold?: boolean; center?: boolean; size?: number } = {}): string {
  const pPr = options.center ? '<w:pPr><w:jc w:val="center"/></w:pPr>' : '';
  const rPr = `<w:rPr>${RUN_FONT}${options.bold ? '<w:b/>' : ''}<w:sz w:val="${options.size ?? 24}"/></w:rPr>`;
  return `<w:p>${pPr}<w:r>${rPr}<w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r></w:p>`;
}

function cell(text: string, span = 1, options: { bold?: boolean; center?: boolean } = {}): string {
  const grid = span > 1 ? `<w:gridSpan w:val="${span}"/>` : '';
  return `<w:tc><w:tcPr>${grid}</w:tcPr>${paragraph(text, { ...options, size: 22 })}</w:tc>`;
}

const GRID_TWIPS = [1000, 600, 1300, 1700, 1100, 1300, 1700, 700];

function documentXml(order: OrderDto, parentNumber: number | null): string {
  const heading = documentHeading(order, parentNumber)
    .map((line, index) => paragraph(line, { bold: index === 0 || index === 3, center: true, size: index === 0 ? 28 : 24 }))
    .join('');
  const header = `<w:tr>${cell('Група', 1, { bold: true, center: true })}${cell('Пара', 1, { bold: true, center: true })}${cell('За розкладом', 2, { bold: true, center: true })}${cell('Причина заміни', 1, { bold: true, center: true })}${cell('По заміні', 2, { bold: true, center: true })}${cell('Ауд', 1, { bold: true, center: true })}</w:tr>`;
  const rows = order.items
    .map((item) => {
      const { cells, mergeLeft, mergeRight } = rowCells(item);
      const left = mergeLeft ? cell(mergeLeft, 2) : cell(cells[2] ?? '') + cell(cells[3] ?? '');
      const right = mergeRight ? cell(mergeRight, 2) : cell(cells[5] ?? '') + cell(cells[6] ?? '');
      return `<w:tr>${cell(cells[0] ?? '')}${cell(cells[1] ?? '')}${left}${cell(cells[4] ?? '')}${right}${cell(cells[7] ?? '')}</w:tr>`;
    })
    .join('');
  const roomSection = `<w:tr>${cell('ЗАМІНА  АУДИТОРІЙ', 8, { center: true })}</w:tr>${order.roomChanges
    .map((item) => `<w:tr>${cell(item.groupCode)}${cell(item.pairLabel)}${cell('', 5)}${cell(item.roomText ?? '')}</w:tr>`)
    .join('')}`;
  const border = '<w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/>';
  const table = `<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders>${border}</w:tblBorders></w:tblPr><w:tblGrid>${GRID_TWIPS.map((w) => `<w:gridCol w:w="${w}"/>`).join('')}</w:tblGrid>${header}${rows}${roomSection}</w:tbl>`;
  const notes = order.notes.map((note) => paragraph(note, { bold: true })).join('');
  const signature = paragraph(`${order.signatoryPosition}\t\t\t${order.signatoryName}`, { bold: true });
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
    `${heading}${table}${paragraph('')}${notes}${paragraph('')}${signature}` +
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="850" w:right="850" w:bottom="850" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr>' +
    '</w:body></w:document>'
  );
}

export function orderDocx(order: OrderDto, parentNumber: number | null): Uint8Array {
  const contentTypes =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>';
  const rels =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>';
  return zipStored([
    { name: '[Content_Types].xml', data: encoder.encode(contentTypes) },
    { name: '_rels/.rels', data: encoder.encode(rels) },
    { name: 'word/document.xml', data: encoder.encode(documentXml(order, parentNumber)) },
  ]);
}
