import axios from "axios";

import {
  AdminApi,
  Configuration,
  ContactApi,
  SubscriptionApi,
  WebsiteAccountApi,
  type AdminAccountBlockRequest,
  type AdminAccountBlockTargetType,
  type ContactMessageRequest,
  type WebsiteAccountDeleteLinkedAccountTypeEnum,
} from "../client";
import type { WebsiteAccountRedeemLinkRequest } from "../client";
import { getAccessToken, login } from "../auth/keycloak";
import { trackEvent } from "./analytics";

const axiosInstance = axios.create();

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status || 0;
    trackEvent("web_api_request_failed", {
      status_code: status,
      path: apiPath(error?.config?.url),
    });
    if (status === 401) {
      void login();
    }
    return Promise.reject(error);
  },
);

async function websiteAccountClient(): Promise<WebsiteAccountApi> {
  const config = await authenticatedConfiguration();
  return new WebsiteAccountApi(config, config.basePath, axiosInstance);
}

async function adminClient(): Promise<AdminApi> {
  const config = await authenticatedConfiguration();
  return new AdminApi(config, config.basePath, axiosInstance);
}

async function subscriptionClient(): Promise<SubscriptionApi> {
  const config = await authenticatedConfiguration();
  return new SubscriptionApi(config, config.basePath, axiosInstance);
}

function contactClient(): ContactApi {
  const config = publicConfiguration();
  return new ContactApi(config, config.basePath, axiosInstance);
}

async function authenticatedConfiguration(): Promise<Configuration> {
  const token = await getAccessToken();
  return new Configuration({
    basePath: import.meta.env.VITE_API_URL || "",
    baseOptions: {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    },
  });
}

function publicConfiguration(): Configuration {
  return new Configuration({
    basePath: import.meta.env.VITE_API_URL || "",
  });
}

export const websiteAccountApi = {
  get: async () => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountGet()).data;
  },

  listLinkedAccounts: async () => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountListLinkedAccounts()).data;
  },

  redeemLink: async (token: string) => {
    const client = await websiteAccountClient();
    const request: WebsiteAccountRedeemLinkRequest = { token };
    return (await client.websiteAccountRedeemLink(request)).data;
  },

  deleteLinkedAccount: async (
    type: WebsiteAccountDeleteLinkedAccountTypeEnum,
    accountKey: string,
  ) => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountDeleteLinkedAccount(type, accountKey)).data;
  },
};

export const contactApi = {
  getConfig: async () => {
    const client = contactClient();
    return (await client.contactGetConfig()).data;
  },

  createMessage: async (request: ContactMessageRequest) => {
    const client = contactClient();
    return (await client.contactCreateMessage(request)).data;
  },
};

export const subscriptionApi = {
  get: async () => {
    const client = await subscriptionClient();
    return (await client.subscriptionGet()).data;
  },

  createCheckout: async (planKey?: string, provider?: string) => {
    const client = await subscriptionClient();
    return (await client.subscriptionCreateCheckout({ plan_key: planKey, provider })).data;
  },

  createPortal: async () => {
    const client = await subscriptionClient();
    return (await client.subscriptionCreatePortal()).data;
  },
};

export const adminApi = {
  getStatistics: async (from: string, to: string) => {
    const client = await adminClient();
    return (await client.adminGetStatistics(from, to)).data;
  },

  getRateLimitUsage: async (limitKey?: string, limit?: number) => {
    const client = await adminClient();
    return (await client.adminGetRateLimitUsage(limitKey, limit)).data;
  },

  listFeedback: async (status: AdminFeedbackFilter = "unread", limit = 100) => {
    const client = await adminClient();
    const feedbackStatus = status as Parameters<AdminApi["adminListFeedback"]>[0];
    return (await client.adminListFeedback(feedbackStatus, limit)).data;
  },

  markFeedbackRead: async (feedbackId: string) => {
    const client = await adminClient();
    return (await client.adminMarkFeedbackRead({ feedback_id: feedbackId })).data;
  },

  markFeedbackUnread: async (feedbackId: string) => {
    const client = await adminClient();
    return (await client.adminMarkFeedbackUnread({ feedback_id: feedbackId })).data;
  },

  listSubscriptions: async (limit = 100) => {
    const client = await adminClient();
    return (await client.adminListSubscriptions(limit)).data;
  },

  syncSubscription: async (subscriptionId: string) => {
    const client = await adminClient();
    return (await client.adminSyncSubscription({ subscription_id: subscriptionId })).data;
  },

  suspendSubscription: async (subscriptionId: string, reason?: string) => {
    const client = await adminClient();
    return (await client.adminSuspendSubscription({ subscription_id: subscriptionId, reason })).data;
  },

  unsuspendSubscription: async (subscriptionId: string) => {
    const client = await adminClient();
    return (await client.adminUnsuspendSubscription({ subscription_id: subscriptionId })).data;
  },

  grantPremium: async (accountId: string) => {
    const client = await adminClient();
    return (await client.adminGrantPremium({ account_id: accountId })).data;
  },

  revokePremium: async (accountId: string) => {
    const client = await adminClient();
    return (await client.adminRevokePremium({ account_id: accountId })).data;
  },

  listAccountBlocks: async (limit = 100) => {
    const client = await adminClient();
    return (await client.adminListAccountBlocks(limit)).data;
  },

  blockAccount: async (
    target: string,
    targetType: AdminAccountBlockTargetType,
    reason?: string,
  ) => {
    const client = await adminClient();
    const request: AdminAccountBlockRequest = {
      target,
      target_type: targetType,
      reason,
    };
    return (await client.adminBlockAccount(request)).data;
  },

  unblockAccount: async (target: string, targetType: AdminAccountBlockTargetType) => {
    const client = await adminClient();
    return (await client.adminUnblockAccount({ target, target_type: targetType })).data;
  },
};

export type AdminFeedbackFilter = "all" | "unread" | "read";

function apiPath(url: string | undefined): string {
  if (!url) {
    return "unknown";
  }
  try {
    return new URL(url, window.location.origin).pathname;
  } catch {
    return "unknown";
  }
}
