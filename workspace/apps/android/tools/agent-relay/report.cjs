'use strict';

// Bots do not always follow the documented report shape: a card may come with free keys ("店名", "人均") and a picture
// as a bare URL or inside the card. The relay normalizes `data` once, so the car always gets
// `card: {title, value?, subtitle?, fields[≤4], items[≤6]}` and `images: [{url, caption}]` to fetch.
const MAX_FIELDS = 4;
const MAX_ITEMS = 6;
const MAX_TEXT = 120;
const CARD_KEYS = new Set(['title', 'value', 'subtitle', 'fields', 'items', 'icon']);
const IMAGE_URL = /^https:\/\/\S+\.(?:jpe?g|png|webp)(?:[?#]\S*)?$/i;

const isObject = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);

function text(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  if (typeof value !== 'string') return '';
  const trimmed = value.trim();
  return trimmed.length > MAX_TEXT ? `${trimmed.slice(0, MAX_TEXT)}…` : trimmed;
}

function imageEntry(entry) {
  if (typeof entry === 'string') return entry.startsWith('https://') ? { url: entry, caption: '' } : null;
  if (!isObject(entry)) return null;
  const url = [entry.url, entry.image_url, entry.src].find(value => typeof value === 'string' && value.startsWith('https://'));
  return url ? { url, caption: text(entry.caption || entry.title || entry.alt) } : null;
}

function documentedRows(card) {
  const fields = Array.isArray(card.fields)
    ? card.fields.filter(isObject).map(row => ({ label: text(row.label), value: text(row.value) })).filter(row => row.label && row.value)
    : [];
  const items = Array.isArray(card.items)
    ? card.items.filter(isObject).map(row => ({ title: text(row.title), detail: text(row.detail) })).filter(row => row.title)
    : [];
  return { fields, items };
}

/** The card in the documented shape plus any picture URLs found among its values; null card when it has no title. */
function normalizeCard(card) {
  if (!isObject(card)) return { card: null, pictures: [] };
  const { fields, items } = documentedRows(card);
  const pictures = [];
  let title = text(card.title);
  for (const [key, raw] of Object.entries(card)) {
    if (CARD_KEYS.has(key)) continue;
    const value = text(raw);
    if (!value) continue;
    if (IMAGE_URL.test(value)) pictures.push({ url: value, caption: text(key) });
    else if (!title) title = value;
    else if (fields.length < MAX_FIELDS) fields.push({ label: text(key), value });
    else if (items.length < MAX_ITEMS) items.push({ title: text(key), detail: value });
  }
  if (!title) return { card: null, pictures };
  const shaped = { title };
  for (const key of ['value', 'subtitle']) if (text(card[key])) shaped[key] = text(card[key]);
  if (fields.length) shaped.fields = fields.slice(0, MAX_FIELDS);
  if (items.length) shaped.items = items.slice(0, MAX_ITEMS);
  return { card: shaped, pictures };
}

/** `data` with a documented card and every picture as `{url, caption}` (bare URLs and card URLs included, no repeats). */
function normalizeReport(data) {
  if (!isObject(data)) return data;
  const { card, pictures } = normalizeCard(data.card);
  const listed = Array.isArray(data.images) ? data.images.map(imageEntry).filter(Boolean) : [];
  const seen = new Set();
  const images = [...listed, ...pictures].filter(image => !seen.has(image.url) && seen.add(image.url));
  const { card: _card, images: _images, ...rest } = data;
  return { ...rest, ...(card ? { card } : {}), ...(images.length ? { images } : {}) };
}

module.exports = { normalizeReport, normalizeCard };
