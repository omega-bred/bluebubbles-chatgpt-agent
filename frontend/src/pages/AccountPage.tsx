import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type {
  WebsiteAccountIdentity,
  AdminSubscriptionItem,
  SubscriptionSummaryResponse,
  WebsiteIntegrationSummary,
  WebsiteLinkedAccountsResponse,
  WebsiteLinkedIntegrationAccount,
} from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { subscriptionApi, websiteAccountApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";
import { displayModelLabel } from "../utils/model-label";

export function AccountPage({ auth }: { auth: AuthState }) {
  const [data, setData] = React.useState<WebsiteLinkedAccountsResponse | null>(null);
  const [subscription, setSubscription] = React.useState<SubscriptionSummaryResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [billingBusy, setBillingBusy] = React.useState(false);
  const hasLoaded = data !== null || error !== null;

  const load = React.useCallback(async () => {
    if (!auth.authenticated) {
      return;
    }
    setLoading(true);
    try {
      const [linkedAccounts, billing] = await Promise.all([
        websiteAccountApi.listLinkedAccounts(),
        subscriptionApi.get(),
      ]);
      setData(linkedAccounts);
      setSubscription(billing);
      trackEvent("web_account_loaded", {
        integration_count: linkedAccounts.integrations?.length || 0,
        is_premium: Boolean(billing.is_premium),
        subscription_count: billing.subscriptions?.length || 0,
      });
      setError(null);
    } catch (err) {
      trackEvent("web_account_load_failed");
      setError(err instanceof Error ? err.message : "Unable to load linked accounts.");
    } finally {
      setLoading(false);
    }
  }, [auth.authenticated]);

  React.useEffect(() => {
    void load();
  }, [load]);

  const createCheckout = React.useCallback(async () => {
    const plan = subscription?.plans?.[0];
    setBillingBusy(true);
    trackEvent("web_checkout_start", { plan_key: plan?.key || "unknown" });
    try {
      const checkout = await subscriptionApi.createCheckout(plan?.key);
      trackEvent("web_checkout_created", {
        provider: checkout.provider || "unknown",
        plan_key: checkout.plan_key || plan?.key || "unknown",
      });
      window.location.assign(checkout.checkout_url);
    } catch (err) {
      trackEvent("web_checkout_failed", { plan_key: plan?.key || "unknown" });
      setError(err instanceof Error ? err.message : "Unable to create checkout.");
    } finally {
      setBillingBusy(false);
    }
  }, [subscription?.plans]);

  const openPortal = React.useCallback(async () => {
    setBillingBusy(true);
    trackEvent("web_billing_portal_start");
    try {
      const portal = await subscriptionApi.createPortal();
      trackEvent("web_billing_portal_created", { provider: portal.provider || "unknown" });
      window.location.assign(portal.portal_url);
    } catch (err) {
      trackEvent("web_billing_portal_failed");
      setError(err instanceof Error ? err.message : "Unable to open billing portal.");
    } finally {
      setBillingBusy(false);
    }
  }, []);

  if (!auth.ready) {
    return <CenteredMessage title="Loading account" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Manage your account" />;
  }

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main">
        <section className="account-heading">
          <p className="eyebrow">Account</p>
          <h1>
            {data?.account?.display_name || data?.account?.preferred_username || "Your account"}
          </h1>
          <p>{data?.account?.email || "Signed in with Keycloak."}</p>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}
        {loading && hasLoaded ? <p className="muted">Refreshing linked accounts.</p> : null}

        <BillingPanel
          subscription={subscription}
          busy={billingBusy}
          onUpgrade={createCheckout}
          onManage={openPortal}
        />

        <section className="linked-list" aria-busy={!hasLoaded || loading}>
          {!hasLoaded ? (
            <AccountLoader />
          ) : (data?.integrations || []).length === 0 ? (
            <EmptyLinks />
          ) : (
            data?.integrations?.map((integration) => (
              <LinkedIdentity
                key={integration.link?.link_id}
                integration={integration}
                onDeleted={load}
              />
            ))
          )}
        </section>
      </main>
    </div>
  );
}

function BillingPanel({
  subscription,
  busy,
  onUpgrade,
  onManage,
}: {
  subscription: SubscriptionSummaryResponse | null;
  busy: boolean;
  onUpgrade: () => Promise<void>;
  onManage: () => Promise<void>;
}) {
  const activeSubscription = subscription?.subscriptions?.[0];
  const plan = subscription?.plans?.[0];
  const isPremium = Boolean(subscription?.is_premium);
  return (
    <article className="billing-panel">
      <div>
        <p className="eyebrow">Billing</p>
        <h2>{isPremium ? "Premium access" : "Free access"}</h2>
        <p className="muted">
          {isPremium
            ? premiumAccessText(subscription, activeSubscription)
            : plan
              ? `${plan.display_name} is ${plan.price_amount} ${plan.currency} ${plan.billing_interval}.`
              : "Premium billing is not configured yet."}
        </p>
      </div>
      <div className="billing-actions">
        {activeSubscription ? (
          <button className="button button-secondary" disabled={busy} onClick={() => void onManage()}>
            {busy ? "Opening" : "Manage billing"}
          </button>
        ) : null}
        <button className="button button-primary" disabled={busy || !plan} onClick={() => void onUpgrade()}>
          {isPremium ? "Extend premium" : "Upgrade"}
        </button>
      </div>
      {activeSubscription ? <SubscriptionRows subscriptions={subscription?.subscriptions || []} /> : null}
    </article>
  );
}

function SubscriptionRows({ subscriptions }: { subscriptions: AdminSubscriptionItem[] }) {
  return (
    <div className="subscription-row-list">
      {subscriptions.slice(0, 3).map((subscription) => (
        <div className="subscription-row" key={subscription.subscription_id}>
          <div>
            <strong>{formatSubscriptionStatus(subscription.status)}</strong>
            <span>{subscription.provider} / {subscription.plan_key}</span>
          </div>
          <p>{subscription.current_period_end ? `Renews ${formatAccountDate(subscription.current_period_end)}` : "No renewal date"}</p>
        </div>
      ))}
    </div>
  );
}

function premiumAccessText(
  subscription: SubscriptionSummaryResponse | null,
  activeSubscription: AdminSubscriptionItem | undefined,
) {
  if (subscription?.premium_until) {
    return `Premium through ${formatAccountDate(subscription.premium_until)}.`;
  }
  if (activeSubscription?.status) {
    return `Premium subscription is ${formatSubscriptionStatus(activeSubscription.status).toLowerCase()}.`;
  }
  return "Premium is active.";
}

function formatSubscriptionStatus(value: string | undefined) {
  if (!value) {
    return "Unknown";
  }
  return value
    .split(/[_-]+/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(" ");
}

function formatAccountDate(value: Date | number | string | null | undefined) {
  if (!value) {
    return "";
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isFinite(date.getTime())
    ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date)
    : "";
}

function AccountLoader() {
  return (
    <article className="account-loader" aria-label="Loading linked accounts">
      <div className="account-loader-top">
        <span className="account-loader-spinner" aria-hidden="true" />
        <div>
          <p className="eyebrow">Account links</p>
          <h2>Loading linked accounts</h2>
        </div>
      </div>
      <div className="account-loader-skeleton" aria-hidden="true">
        <span className="skeleton-line wide" />
        <span className="skeleton-line medium" />
        <span className="skeleton-pill-row">
          <span className="skeleton-pill" />
          <span className="skeleton-pill" />
          <span className="skeleton-pill short" />
        </span>
      </div>
    </article>
  );
}

function LinkedIdentity({
  integration,
  onDeleted,
}: {
  integration: WebsiteIntegrationSummary;
  onDeleted: () => Promise<void>;
}) {
  const [unlinkingAccountKey, setUnlinkingAccountKey] = React.useState<string | null>(null);
  const link = integration.link;
  if (!link) {
    return null;
  }
  const calendars = integration.gcal_accounts || [];
  const linkedAccounts = integration.linked_accounts || [];
  const modelAccess = integration.model_access;
  const modelLabel = modelAccess ? displayModelLabel(modelAccess.current_model_label) : "";
  const accountLabel = modelAccess?.is_premium ? "Premium" : "Free";
  const accessNote =
    modelAccess && modelAccess.is_premium
      ? `${accountLabel} account · ${modelLabel}`
      : `${accountLabel} account`;
  return (
    <article className="linked-item">
      <div>
        <p className="eyebrow">Chat identities</p>
        <h2>Linked chat addresses</h2>
        <p className="muted">These addresses are treated as the same user across tools.</p>
        {modelAccess ? <p className="model-note">{accessNote}</p> : null}
      </div>
      <div className="integration-pills">
        {modelAccess ? (
          <span className={modelAccess.is_premium ? "pill premium" : "pill"}>
            {modelLabel === "Free" ? "Free" : `Model ${modelLabel}`}
          </span>
        ) : null}
        <span className={integration.coder_linked ? "pill good" : "pill"}>
          Coder {integration.coder_linked ? "linked" : "not linked"}
        </span>
        <span className={calendars.length > 0 ? "pill good" : "pill"}>
          Google Calendar{" "}
          {calendars.length > 0
            ? calendars.map((item) => item.account_id).join(", ")
            : "not linked"}
        </span>
      </div>
      {linkedAccounts.length > 0 ? (
        <div className="linked-account-list">
          {(link.identities || []).map((identity) => (
            <IdentityRow
              key={`${identity.type}:${identity.normalized_identifier}`}
              identity={identity}
            />
          ))}
          {linkedAccounts.map((account) => (
            <LinkedAccountRow
              key={`${account.type}:${account.account_key}`}
              account={account}
              unlinking={unlinkingAccountKey === account.account_key}
              onUnlink={async () => {
                setUnlinkingAccountKey(account.account_key);
                trackEvent("web_integration_unlink_start", { type: account.type });
                try {
                  await websiteAccountApi.deleteLinkedAccount(account.type, account.account_key);
                  trackEvent("web_integration_unlinked", { type: account.type });
                  await onDeleted();
                } catch (err) {
                  trackEvent("web_integration_unlink_failed", { type: account.type });
                  throw err;
                } finally {
                  setUnlinkingAccountKey(null);
                }
              }}
            />
          ))}
        </div>
      ) : null}
      {linkedAccounts.length === 0 && (link.identities || []).length > 0 ? (
        <div className="linked-account-list">
          {(link.identities || []).map((identity) => (
            <IdentityRow
              key={`${identity.type}:${identity.normalized_identifier}`}
              identity={identity}
            />
          ))}
        </div>
      ) : null}
    </article>
  );
}

function IdentityRow({ identity }: { identity: WebsiteAccountIdentity }) {
  return (
    <div className="linked-account-row">
      <div>
        <p className="linked-account-type">{identityTypeLabel(identity.type)}</p>
        <p className="linked-account-email">{identity.identifier}</p>
      </div>
    </div>
  );
}

function LinkedAccountRow({
  account,
  unlinking,
  onUnlink,
}: {
  account: WebsiteLinkedIntegrationAccount;
  unlinking: boolean;
  onUnlink: () => Promise<void>;
}) {
  const typeLabel = account.type === "gcal" ? "Google Calendar" : "Coder";
  const identifier = account.email || account.account_key;
  return (
    <div className="linked-account-row">
      <div>
        <p className="linked-account-type">{typeLabel}</p>
        <p className="linked-account-email">{identifier}</p>
      </div>
      <button
        className="button button-secondary compact"
        disabled={!account.unlinkable || unlinking}
        onClick={() => void onUnlink()}
      >
        {unlinking ? "Unlinking" : "Unlink"}
      </button>
    </div>
  );
}

function EmptyLinks() {
  return (
    <article className="empty-state">
      <h2>No iMessage senders linked yet.</h2>
      <p>
        Text the bot and ask it to link your website account. It will send a short-lived login link
        back in iMessage.
      </p>
    </article>
  );
}

function identityTypeLabel(type: WebsiteAccountIdentity["type"]) {
  switch (type) {
    case "imessage_email":
      return "iMessage email";
    case "imessage_phone":
      return "iMessage phone";
    case "lxmf_address":
      return "LXMF address";
    default:
      return "Chat address";
  }
}
