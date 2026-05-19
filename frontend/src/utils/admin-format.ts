const numberFormat = new Intl.NumberFormat();
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});
const bucketDateFormat = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
  hour: "numeric",
  minute: "2-digit",
});
const epochSecondsCutoff = 10_000_000_000;

export type ApiDateValue = Date | number | string | null | undefined;

export function toLocalInputValue(date: Date): string {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

export function toIso(value: string): string {
  return new Date(value).toISOString();
}

export function formatCount(value: number | undefined): string {
  return numberFormat.format(value || 0);
}

export function formatPercent(value: number | undefined): string {
  return `${Math.round((value || 0) * 100)}%`;
}

export function formatDurationMs(value: number | undefined): string {
  if (!value || value < 1) {
    return "0 ms";
  }
  if (value < 1000) {
    return `${Math.round(value)} ms`;
  }
  return `${(value / 1000).toFixed(1)} s`;
}

export function formatTitleLabel(value: string | undefined, fallback: string): string {
  if (!value) {
    return fallback;
  }
  return value
    .split(/[_-]+/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(" ");
}

export function formatDateTime(value: ApiDateValue): string {
  const date = parseApiDate(value);
  if (!date) {
    return "";
  }
  return dateTimeFormat.format(date);
}

export function formatBucket(value: ApiDateValue): string {
  const date = parseApiDate(value);
  if (!date) {
    return "";
  }
  return bucketDateFormat.format(date);
}

function parseApiDate(value: ApiDateValue): Date | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  if (value instanceof Date) {
    return isValidDate(value) ? value : null;
  }
  if (typeof value === "number") {
    const milliseconds = Math.abs(value) < epochSecondsCutoff ? value * 1000 : value;
    const date = new Date(milliseconds);
    return isValidDate(date) ? date : null;
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return parseApiDate(Number(trimmed));
  }
  const date = new Date(trimmed);
  return isValidDate(date) ? date : null;
}

function isValidDate(date: Date): boolean {
  return Number.isFinite(date.getTime());
}
