// import type { Group as ApiGroup, DeviceConfig } from "../client";

export interface Group /*extends ApiGroup */ {
  /** Additional UI properties not yet provided by the API */
  description?: string;
  membersCount?: number;
  framesCount?: number;
  coverImage?: string;
  lastUpdated?: string;
  isOwner?: boolean;
  photoAlbums?: PhotoAlbum[];
  frames: Frame[];
}

export interface PhotoAlbum {
  id: string;
  name: string;
  source: 'apple' | 'google';
  photoCount: number;
}

export interface Frame /*extends DeviceConfig*/ {
  serialNumber?: string;
  batteryLevel?: number;
  lastUpdated?: string;
  lastPhotoDisplayed?: string;
  status?: 'online' | 'offline' | 'sleep';
  /**
   * The API uses snake_case `group_id` but the UI expects camelCase
   */
  groupId?: string;
  advancedConfig?: {
    pinCode?: string;
    refreshInterval?: number;
    brightness?: number;
  };
}
