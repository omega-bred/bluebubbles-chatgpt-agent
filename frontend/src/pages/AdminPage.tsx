import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type {
  AdminAccountBlockListResponse,
  AdminAccountBlockTargetType,
  AdminBlockedAccountItem,
  AdminPremiumGrantTargetType,
  AdminRateLimitUsage,
  AdminRateLimitUsageResponse,
  AdminSenderStats,
  AdminStatsBucket,
  AdminSubscriptionItem,
  AdminSubscriptionListResponse,
  AdminStatsResponse,
  AdminToolAccountTypeStats,
  AdminToolStats,
} from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { adminApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";

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

const accountIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function inferPremiumGrantTargetType(target: string): AdminPremiumGrantTargetType | undefined {
  if (accountIdPattern.test(target)) {
    return "account_id";
  }
  if (target.includes("@")) {
    return "email";
  }
  const digitCount = target.replace(/\D/g, "").length;
  if (digitCount >= 7) {
    return "phone";
  }
  return undefined;
}

type ApiDateValue = Date | number | string | null | undefined;
type BlockAction = "block" | "unblock";

const accountBlockTargetTypes: Array<{ value: AdminAccountBlockTargetType; label: string }> = [
  { value: "account_id", label: "Account ID" },
  { value: "imessage_email", label: "BlueChat email" },
  { value: "imessage_phone", label: "BlueChat phone" },
  { value: "lxmf_address", label: "LXMF address" },
  { value: "website_subject", label: "Website subject" },
  { value: "website_email", label: "Website email" },
];

export function AdminPage({ auth }: { auth: AuthState }) {
  const [fromInput, setFromInput] = React.useState(() =>
    toLocalInputValue(new Date(Date.now() - 24 * 60 * 60 * 1000)),
  );
  const [toInput, setToInput] = React.useState(() => toLocalInputValue(new Date()));
  const [data, setData] = React.useState<AdminStatsResponse | null>(null);
  const [limitData, setLimitData] = React.useState<AdminRateLimitUsageResponse | null>(null);
  const [subscriptionData, setSubscriptionData] = React.useState<AdminSubscriptionListResponse | null>(null);
  const [accountBlockData, setAccountBlockData] =
    React.useState<AdminAccountBlockListResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [subscriptionAction, setSubscriptionAction] = React.useState<string | null>(null);
  const [manualPremiumAction, setManualPremiumAction] = React.useState<"grant" | "revoke" | null>(null);
  const [manualPremiumTarget, setManualPremiumTarget] = React.useState("");
  const [blockTargetType, setBlockTargetType] =
    React.useState<AdminAccountBlockTargetType>("account_id");
  const [blockTarget, setBlockTarget] = React.useState("");
  const [blockReason, setBlockReason] = React.useState("");
  const [blockAction, setBlockAction] = React.useState<string | null>(null);

  const load = React.useCallback(async () => {
    if (!auth.authenticated || !auth.admin || !fromInput || !toInput) {
      return;
    }
    setLoading(true);
    try {
      const [stats, limits, subscriptions, accountBlocks] = await Promise.all([
        adminApi.getStatistics(toIso(fromInput), toIso(toInput)),
        adminApi.getRateLimitUsage(),
        adminApi.listSubscriptions(),
        adminApi.listAccountBlocks(),
      ]);
      setData(stats);
      setLimitData(limits);
      setSubscriptionData(subscriptions);
      setAccountBlockData(accountBlocks);
      trackEvent("web_admin_stats_loaded", {
        message_count: stats.total_messages || 0,
        tool_call_count: stats.total_tool_calls || 0,
        subscription_count: subscriptions.subscriptions?.length || 0,
        blocked_account_count: accountBlocks.accounts?.length || 0,
      });
      setError(null);
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      trackEvent("web_admin_stats_failed", { status_code: status || 0 });
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

  const runSubscriptionAction = React.useCallback(
    async (action: "sync" | "suspend" | "unsuspend", subscriptionId: string) => {
      setSubscriptionAction(`${action}:${subscriptionId}`);
      try {
        if (action === "sync") {
          await adminApi.syncSubscription(subscriptionId);
        } else if (action === "suspend") {
          await adminApi.suspendSubscription(subscriptionId, "Suspended from admin UI");
        } else {
          await adminApi.unsuspendSubscription(subscriptionId);
        }
        trackEvent("web_admin_subscription_action", { action, status: "success" });
        setSubscriptionData(await adminApi.listSubscriptions());
        setError(null);
      } catch (err) {
        trackEvent("web_admin_subscription_action", { action, status: "failed" });
        setError(err instanceof Error ? err.message : "Unable to update subscription.");
      } finally {
        setSubscriptionAction(null);
      }
    },
    [],
  );

  const runManualPremiumAction = React.useCallback(
    async (action: "grant" | "revoke") => {
      const target = manualPremiumTarget.trim();
      if (!target) {
        setError("Enter an email, phone, or account id before changing premium access.");
        return;
      }
      setManualPremiumAction(action);
      try {
        const targetType = inferPremiumGrantTargetType(target);
        if (action === "grant") {
          await adminApi.grantPremium(target, targetType);
        } else {
          await adminApi.revokePremium(target, targetType);
        }
        trackEvent("web_admin_premium_action", { action, status: "success" });
        setSubscriptionData(await adminApi.listSubscriptions());
        setManualPremiumTarget("");
        setError(null);
      } catch (err) {
        trackEvent("web_admin_premium_action", { action, status: "failed" });
        setError(err instanceof Error ? err.message : "Unable to update premium access.");
      } finally {
        setManualPremiumAction(null);
      }
    },
    [manualPremiumTarget],
  );

  const runAccountBlockAction = React.useCallback(
    async (action: BlockAction, target = blockTarget, targetType = blockTargetType) => {
      const trimmedTarget = target.trim();
      if (!trimmedTarget) {
        setError("Enter an account or identity before changing processing access.");
        return;
      }
      setBlockAction(`${action}:${trimmedTarget}`);
      try {
        if (action === "block") {
          await adminApi.blockAccount(trimmedTarget, targetType, blockReason.trim() || undefined);
        } else {
          await adminApi.unblockAccount(trimmedTarget, targetType);
        }
        trackEvent("web_admin_account_block_action", { action, target_type: targetType, status: "success" });
        setAccountBlockData(await adminApi.listAccountBlocks());
        if (target === blockTarget) {
          setBlockTarget("");
          setBlockReason("");
        }
        setError(null);
      } catch (err) {
        trackEvent("web_admin_account_block_action", { action, target_type: targetType, status: "failed" });
        setError(err instanceof Error ? err.message : "Unable to update account block.");
      } finally {
        setBlockAction(null);
      }
    },
    [blockReason, blockTarget, blockTargetType],
  );

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
  const topTool = data?.tools?.[0];
  const topLimitUsage = limitData?.usages?.[0];
  const subscriptionStats = subscriptionData?.stats;

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
          <nav className="admin-page-nav" aria-label="Admin pages">
            <a className="active" href="/admin">
              Statistics
            </a>
            <a href="/admin/feedback">Feedback</a>
          </nav>
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
          <MetricTile label="Tool calls" value={formatCount(data?.total_tool_calls)} />
          <MetricTile label="Tool success" value={formatPercent(data?.tool_success_rate)} />
          <MetricTile
            label="Subscriptions"
            value={formatCount(subscriptionStats?.active_subscriptions)}
          />
          <MetricTile
            label="Monthly recurring"
            value={formatMoney(subscriptionStats?.monthly_recurring_amount, subscriptionStats?.currency)}
          />
          <MetricTile label="Top model" value={topModel?.model_label || "None"} />
          <MetricTile label="Top tool" value={formatToolLabel(topTool?.tool_name)} />
          <MetricTile
            label="Highest quota use"
            value={formatPercent(topLimitUsage?.percentage)}
          />
          <MetricTile label="Blocked accounts" value={formatCount(accountBlockData?.accounts?.length)} />
        </section>

        <section className="admin-content-grid">
          <article className="admin-panel wide-panel">
            <header>
              <p className="eyebrow">Abuse controls</p>
              <h2>Account processing blocks</h2>
            </header>
            <AccountBlockAdmin
              action={blockAction}
              blockedAccounts={accountBlockData?.accounts || []}
              reason={blockReason}
              target={blockTarget}
              targetType={blockTargetType}
              onAction={runAccountBlockAction}
              onReasonChange={setBlockReason}
              onTargetChange={setBlockTarget}
              onTargetTypeChange={setBlockTargetType}
            />
          </article>

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
                  <div className="model-usage-row" key={`${model.model_key}-${String(model.is_premium)}`}>
                    <div>
                      <strong>{model.model_label}</strong>
                      <span>
                        {model.responses_model} / {model.is_premium ? "Paid" : "Free"}
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
              <p className="eyebrow">Senders</p>
              <h2>Top accounts</h2>
            </header>
            <SenderUsage senders={data?.senders || []} />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Rate limits</p>
              <h2>Daily response usage</h2>
            </header>
            <RateLimitUsage usages={limitData?.usages || []} />
          </article>

          <article className="admin-panel wide-panel">
            <header>
              <p className="eyebrow">Payments</p>
              <h2>Subscriptions</h2>
            </header>
            <SubscriptionAdmin
              subscriptions={subscriptionData?.subscriptions || []}
              action={subscriptionAction}
              manualPremiumTarget={manualPremiumTarget}
              manualAction={manualPremiumAction}
              onAction={runSubscriptionAction}
              onManualPremiumTargetChange={setManualPremiumTarget}
              onManualAction={runManualPremiumAction}
            />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Tools</p>
              <h2>Usage by tool</h2>
            </header>
            <ToolUsage tools={data?.tools || []} />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Account types</p>
              <h2>Tool success mix</h2>
            </header>
            <ToolAccountTypes accountTypes={data?.tool_account_types || []} />
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

function AccountBlockAdmin({
  action,
  blockedAccounts,
  reason,
  target,
  targetType,
  onAction,
  onReasonChange,
  onTargetChange,
  onTargetTypeChange,
}: {
  action: string | null;
  blockedAccounts: AdminBlockedAccountItem[];
  reason: string;
  target: string;
  targetType: AdminAccountBlockTargetType;
  onAction: (action: BlockAction, target?: string, targetType?: AdminAccountBlockTargetType) => Promise<void>;
  onReasonChange: (value: string) => void;
  onTargetChange: (value: string) => void;
  onTargetTypeChange: (value: AdminAccountBlockTargetType) => void;
}) {
  return (
    <div className="account-block-stack">
      <div className="account-block-controls">
        <label>
          <span>Identity type</span>
          <select
            value={targetType}
            onChange={(event) => onTargetTypeChange(event.target.value as AdminAccountBlockTargetType)}
          >
            {accountBlockTargetTypes.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>Account or identity</span>
          <input
            value={target}
            onChange={(event) => onTargetChange(event.target.value)}
            placeholder="account id, email, phone, or LXMF address"
          />
        </label>
        <label>
          <span>Reason</span>
          <input
            value={reason}
            onChange={(event) => onReasonChange(event.target.value)}
            placeholder="abuse, spam, chargeback, etc."
          />
        </label>
        <button
          className="button compact button-primary"
          disabled={action !== null}
          onClick={() => void onAction("block")}
        >
          {action?.startsWith("block:") ? "Blocking" : "Block"}
        </button>
        <button
          className="button compact button-secondary"
          disabled={action !== null}
          onClick={() => void onAction("unblock")}
        >
          {action?.startsWith("unblock:") ? "Unblocking" : "Unblock"}
        </button>
      </div>

      {blockedAccounts.length === 0 ? (
        <p className="muted">No accounts are currently blocked from processing.</p>
      ) : (
        <div className="account-block-list">
          {blockedAccounts.map((account) => {
            const busy = action?.endsWith(account.account_id || "") || false;
            return (
              <div className="account-block-row" key={account.account_id}>
                <div>
                  <strong>#{account.account_bucket || "unknown"}</strong>
                  <span>
                    {account.reason || "No reason"} / {formatDateTime(account.blocked_at)}
                  </span>
                  <p>{identitySummary(account)}</p>
                </div>
                <button
                  className="button compact button-secondary"
                  disabled={busy}
                  onClick={() => void onAction("unblock", account.account_id, "account_id")}
                >
                  Unblock
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function SubscriptionAdmin({
  subscriptions,
  action,
  manualPremiumTarget,
  manualAction,
  onAction,
  onManualPremiumTargetChange,
  onManualAction,
}: {
  subscriptions: AdminSubscriptionItem[];
  action: string | null;
  manualPremiumTarget: string;
  manualAction: "grant" | "revoke" | null;
  onAction: (action: "sync" | "suspend" | "unsuspend", subscriptionId: string) => Promise<void>;
  onManualPremiumTargetChange: (value: string) => void;
  onManualAction: (action: "grant" | "revoke") => Promise<void>;
}) {
  return (
    <div className="subscription-admin-stack">
      <div className="subscription-manual-controls">
        <label>
          <span>Email or phone</span>
          <input
            value={manualPremiumTarget}
            onChange={(event) => onManualPremiumTargetChange(event.target.value)}
            placeholder="name@example.com or +1 555 123 4567"
          />
        </label>
        <button
          className="button compact button-primary"
          disabled={manualAction !== null}
          onClick={() => void onManualAction("grant")}
        >
          {manualAction === "grant" ? "Granting" : "Grant premium"}
        </button>
        <button
          className="button compact button-secondary"
          disabled={manualAction !== null}
          onClick={() => void onManualAction("revoke")}
        >
          {manualAction === "revoke" ? "Revoking" : "Revoke manual"}
        </button>
      </div>
      {subscriptions.length === 0 ? (
        <p className="muted">No subscriptions yet.</p>
      ) : (
        <div className="subscription-admin-list">
          {subscriptions.map((subscription) => {
            const busy = action?.endsWith(subscription.subscription_id || "") || false;
            const suspended = subscription.status === "suspended";
            return (
              <div className="subscription-admin-row" key={subscription.subscription_id}>
                <div>
                  <strong>#{subscription.account_bucket || "unknown"}</strong>
                  <span>
                    {formatSubscriptionStatus(subscription.status)} / {subscription.provider} / {subscription.plan_key}
                  </span>
                  <p>
                    {subscription.provider_status ? `${subscription.provider_status} / ` : ""}
                    {subscription.current_period_end
                      ? `renews ${formatDateTime(subscription.current_period_end)}`
                      : "no renewal date"}
                  </p>
                </div>
                <div className="subscription-admin-actions">
                  <button
                    className="button compact button-secondary"
                    disabled={busy}
                    onClick={() => void onAction("sync", subscription.subscription_id)}
                  >
                    Sync
                  </button>
                  <button
                    className="button compact button-secondary"
                    disabled={busy}
                    onClick={() =>
                      void onAction(suspended ? "unsuspend" : "suspend", subscription.subscription_id)
                    }
                  >
                    {suspended ? "Unsuspend" : "Suspend"}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function formatSubscriptionStatus(value: string | undefined): string {
  return formatToolLabel(value || "unknown");
}

function formatMoney(amount: string | undefined, currency: string | undefined): string {
  const numeric = Number(amount || 0);
  if (!Number.isFinite(numeric)) {
    return `${amount || "0"} ${currency || ""}`.trim();
  }
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: currency || "USD",
    maximumFractionDigits: 2,
  }).format(numeric);
}

function ToolUsage({ tools }: { tools: AdminToolStats[] }) {
  const max = Math.max(1, ...tools.map((tool) => tool.call_count || 0));
  if (tools.length === 0) {
    return <p className="muted">No tool calls in this period.</p>;
  }
  return (
    <div className="tool-usage-list">
      {tools.map((tool) => {
        const count = tool.call_count || 0;
        return (
          <div className="tool-usage-row" key={`${tool.tool_category}-${tool.tool_name}`}>
            <div>
              <strong>{formatToolLabel(tool.tool_name)}</strong>
              <span>
                {formatCategory(tool.tool_category)} / last used {formatDateTime(tool.last_used_at)}
              </span>
            </div>
            <div className="tool-usage-meter" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <p>
              {formatCount(count)} calls / {formatCount(tool.successful_calls)} ok /{" "}
              {formatCount(tool.failed_calls)} failed / {formatPercent(tool.success_rate)} success
              / avg {formatDurationMs(tool.average_duration_ms)}
            </p>
          </div>
        );
      })}
    </div>
  );
}

function ToolAccountTypes({
  accountTypes,
}: {
  accountTypes: AdminToolAccountTypeStats[];
}) {
  const max = Math.max(1, ...accountTypes.map((accountType) => accountType.call_count || 0));
  if (accountTypes.length === 0) {
    return <p className="muted">No tool calls by account type in this period.</p>;
  }
  return (
    <div className="tool-account-type-list">
      {accountTypes.map((accountType) => {
        const count = accountType.call_count || 0;
        return (
          <div
            className="tool-account-type-row"
            key={String(accountType.is_premium)}
          >
            <div>
              <strong>{accountType.is_premium ? "Paid" : "Free"}</strong>
              <span>{accountType.is_premium ? "Premium accounts" : "Free accounts"}</span>
            </div>
            <div className="tool-account-type-meter" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <p>
              {formatCount(count)} calls / {formatCount(accountType.active_users)} users /{" "}
              {formatPercent(accountType.success_rate)} success /{" "}
              {formatPercent(accountType.percentage)} of tool volume
            </p>
          </div>
        );
      })}
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

function SenderUsage({ senders }: { senders: AdminSenderStats[] }) {
  const max = Math.max(1, ...senders.map((sender) => sender.message_count || 0));
  if (senders.length === 0) {
    return <p className="muted">No sender buckets in this period.</p>;
  }
  return (
    <div className="sender-usage-list">
      {senders.map((sender) => {
        const count = sender.message_count || 0;
        return (
          <div className="sender-usage-row" key={sender.account_key_hash}>
            <div>
              <strong>#{sender.account_bucket || "unknown"}</strong>
              <span title={sender.account_key_hash}>
                Last seen {formatDateTime(sender.last_seen_at)}
              </span>
            </div>
            <div className="sender-usage-meter" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <p>
              {formatCount(count)} messages / {formatPercent(sender.percentage)} of volume /{" "}
              {senderModelSummary(sender)}
            </p>
          </div>
        );
      })}
    </div>
  );
}

function RateLimitUsage({ usages }: { usages: AdminRateLimitUsage[] }) {
  if (usages.length === 0) {
    return <p className="muted">No rate-limit usage in the current monthly window.</p>;
  }
  return (
    <div className="rate-limit-usage-list">
      {usages.map((usage) => (
        <div className="rate-limit-usage-row" key={`${usage.limit_key}-${usage.scope_key}`}>
          <div>
            <strong>#{usage.account_bucket || "unknown"}</strong>
            <span>
              {usage.is_premium ? "Paid" : "Free"} / resets {formatDateTime(usage.window_end)}
            </span>
          </div>
          <div className="rate-limit-usage-meter" aria-hidden="true">
            <span style={{ width: `${Math.max(usage.percentage || 0, 0.02) * 100}%` }} />
          </div>
          <p>
            {formatCount(usage.used)} of {formatCount(usage.limit)} used /{" "}
            {formatCount(usage.remaining)} remaining
          </p>
        </div>
      ))}
    </div>
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

function formatDurationMs(value: number | undefined): string {
  if (!value || value < 1) {
    return "0 ms";
  }
  if (value < 1000) {
    return `${Math.round(value)} ms`;
  }
  return `${(value / 1000).toFixed(1)} s`;
}

function formatToolLabel(value: string | undefined): string {
  if (!value) {
    return "None";
  }
  return value
    .split(/[_-]+/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(" ");
}

function formatCategory(value: string | undefined): string {
  return formatToolLabel(value || "other");
}

function identitySummary(account: AdminBlockedAccountItem): string {
  const identities = (account.identities || [])
    .slice(0, 3)
    .map((identity) => `${formatToolLabel(identity.type)}: ${identity.identifier}`)
    .filter(Boolean);
  if (account.website_email) {
    identities.unshift(`Website: ${account.website_email}`);
  }
  return identities.length === 0 ? account.account_id || "No linked identities" : identities.join(" / ");
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

function senderModelSummary(sender: AdminSenderStats): string {
  const models = sender.models || [];
  if (models.length === 0) {
    return "No model mix";
  }
  return models
    .slice(0, 2)
    .map((model) => `${model.model_label}: ${formatCount(model.message_count)}`)
    .join(" / ");
}
