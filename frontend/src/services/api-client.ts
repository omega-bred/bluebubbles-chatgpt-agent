import axios from "axios";

import {
  AdminApi,
  Configuration,
  WebsiteAccountApi,
  type WebsiteAccountDeleteLinkedAccountTypeEnum,
} from "../client";
import type { WebsiteAccountRedeemLinkRequest } from "../client";
import { getAccessToken, login } from "../auth/keycloak";

const axiosInstance = axios.create();

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
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

export const adminApi = {
  getStatistics: async (from: string, to: string) => {
    const client = await adminClient();
    return (await client.adminGetStatistics(from, to)).data;
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
};

export type AdminFeedbackFilter = "all" | "unread" | "read";
