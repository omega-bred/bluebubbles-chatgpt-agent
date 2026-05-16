import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type { AdminStatsBucket, AdminStatsResponse } from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { adminApi } from "../services/api-client";

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

type ApiDateValue = Date | number | string | null | undefined;

export function AdminPage({ auth }: { auth: AuthState }) {
  const [fromInput, setFromInput] = React.useState(() =>
    toLocalInputValue(new Date(Date.now() - 24 * 60 * 60 * 1000)),
  );
  const [toInput, setToInput] = React.useState(() => toLocalInputValue(new Date()));
  const [data, setData] = React.useState<AdminStatsResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);

  const load = React.useCallback(async () => {
    if (!auth.authenticated || !auth.admin || !fromInput || !toInput) {
      return;
    }
    setLoading(true);
    try {
      setData(await adminApi.getStatistics(toIso(fromInput), toIso(toInput)));
      setError(null);
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      setError(
        status === 403
          ? "Your token is missing the bbagent-admin-role role."
          : err instanceof Error
            ? err.message
            : "Unable to load admin statistics.",
      );
    } finally {
      setLoading(false);
    }
  }, [auth.admin, auth.authenticated, fromInput, toInput]);

  React.useEffect(() => {
    void load();
  }, [load]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading admin" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Admin statistics" />;
  }

  if (!auth.admin) {
    return (
      <CenteredMessage
        title="Admin access required"
        body="Sign in with an account that has bbagent-admin-role."
      />
    );
  }

  const topModel = data?.models?.[0];

  return (
    <div className="account-shell admin-shell">
      <SiteNav auth={auth} />
      <main className="account-main admin-main">
        <section className="account-heading admin-heading">
          <p className="eyebrow">Admin</p>
          <h1>Agent statistics</h1>
          <p>
            {data?.period
              ? `${formatDateTime(data.period.from)} to ${formatDateTime(data.period.to)}`
              : "Recent message activity"}
          </p>
        </section>

        <section className="admin-toolbar">
          <div className="segmented-control" aria-label="Statistics period">
            <button onClick={() => applyPreset(24, setFromInput, setToInput)}>24h</button>
            <button onClick={() => applyPreset(24 * 7, setFromInput, setToInput)}>7d</button>
            <button onClick={() => applyPreset(24 * 30, setFromInput, setToInput)}>30d</button>
          </div>
          <label>
            <span>From</span>
            <input
              type="datetime-local"
              value={fromInput}
              onChange={(event) => setFromInput(event.target.value)}
            />
          </label>
          <label>
            <span>To</span>
            <input
              type="datetime-local"
              value={toInput}
              onChange={(event) => setToInput(event.target.value)}
            />
          </label>
          <button className="button button-primary" disabled={loading} onClick={() => void load()}>
            {loading ? "Refreshing" : "Refresh"}
          </button>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}

        <section className="admin-stat-grid">
          <MetricTile label="Messages" value={formatCount(data?.total_messages)} />
          <MetricTile label="Active users" value={formatCount(data?.active_users)} />
          <MetricTile
            label="Messages per user"
            value={(data?.average_messages_per_user || 0).toFixed(1)}
          />
          <MetricTile label="Top model" value={topModel?.model_label || "None"} />
        </section>

        <section className="admin-content-grid">
          <article className="admin-panel">
            <header>
              <p className="eyebrow">Models</p>
              <h2>Served by model</h2>
            </header>
            <div className="model-usage-list">
              {(data?.models || []).length === 0 ? (
                <p className="muted">No messages in this period.</p>
              ) : (
                data?.models?.map((model) => (
                  <div className="model-usage-row" key={`${model.model_key}-${model.plan}`}>
                    <div>
                      <strong>{model.model_label}</strong>
                      <span>
                        {model.responses_model} / {model.plan}
                      </span>
                    </div>
                    <div className="model-usage-meter" aria-hidden="true">
                      <span style={{ width: `${Math.max(model.percentage || 0, 0.02) * 100}%` }} />
                    </div>
                    <p>
                      {formatCount(model.message_count)} messages /{" "}
                      {formatCount(model.active_users)} users / {formatPercent(model.percentage)}
                    </p>
                  </div>
                ))
              )}
            </div>
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Timeline</p>
              <h2>Message volume</h2>
            </header>
            <Timeline buckets={data?.timeline || []} />
          </article>
        </section>
      </main>
    </div>
  );
}

function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <article className="metric-tile">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function Timeline({ buckets }: { buckets: AdminStatsBucket[] }) {
  const max = Math.max(1, ...buckets.map((bucket) => bucket.message_count || 0));
  if (buckets.length === 0) {
    return <p className="muted">No timeline buckets available.</p>;
  }
  return (
    <div className="timeline-list">
      {buckets.map((bucket) => {
        const count = bucket.message_count || 0;
        return (
          <div className="timeline-row" key={`${bucket.bucket_start}-${bucket.bucket_end}`}>
            <time>{formatBucket(bucket.bucket_start)}</time>
            <div className="timeline-bar" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <strong>{formatCount(count)}</strong>
            <small>{bucketModelSummary(bucket)}</small>
          </div>
        );
      })}
    </div>
  );
}

function applyPreset(
  hours: number,
  setFromInput: (value: string) => void,
  setToInput: (value: string) => void,
) {
  const to = new Date();
  const from = new Date(to.getTime() - hours * 60 * 60 * 1000);
  setFromInput(toLocalInputValue(from));
  setToInput(toLocalInputValue(to));
}

function toLocalInputValue(date: Date): string {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function toIso(value: string): string {
  return new Date(value).toISOString();
}

function formatCount(value: number | undefined): string {
  return numberFormat.format(value || 0);
}

function formatPercent(value: number | undefined): string {
  return `${Math.round((value || 0) * 100)}%`;
}

function formatDateTime(value: ApiDateValue): string {
  const date = parseApiDate(value);
  if (!date) {
    return "";
  }
  return dateTimeFormat.format(date);
}

function formatBucket(value: ApiDateValue): string {
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

function bucketModelSummary(bucket: AdminStatsBucket): string {
  const models = bucket.models || [];
  if (models.length === 0) {
    return "No messages";
  }
  return models
    .map((model) => `${model.model_label}: ${formatCount(model.message_count)}`)
    .join(" / ");
}
