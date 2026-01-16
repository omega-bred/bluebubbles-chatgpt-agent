import { Configuration,  } from "../client";
import { Group, Frame, PhotoAlbum } from "../types/models";

import axios from "axios";

// Create a configured API client using Clerk's access token
// async function createUserApi(): Promise<UserApi> {
//   try {
//     const token = await window.Clerk?.session?.getToken();
//
//     if (!token) {
//       window.Clerk?.redirectToSignIn();
//       throw new Error("Missing Clerk session token");
//     }
//
//     const axiosInstance = axios.create();
//     axiosInstance.interceptors.response.use(
//       response => response,
//       error => {
//         if (error?.response?.status === 401) {
//           window.Clerk?.redirectToSignIn();
//         }
//         return Promise.reject(error);
//       }
//     );
//
//     const config = new Configuration({
//       basePath: import.meta.env.VITE_API_URL || "",
//       accessToken: token,
//     });
//
//     return new UserApi(config, config.basePath, axiosInstance);
//   } catch (error) {
//     window.Clerk?.redirectToSignIn();
//     return Promise.reject(error);
//   }
// }
//
// function toFrame(d: DeviceConfig): Frame {
//   return {
//         ...d,
//         id: d.id ?? "",
//         serialNumber: d.id ?? "",
//         batteryLevel: d.last_battery_level,
//         status: "online",
//         lastUpdated: new Date().toLocaleDateString(),
//         groupId: d.group_id
//       };
// }
//
// export const groupsApi = {
//   getAll: async (): Promise<Group[]> => {
//     try {
//       const client = await createUserApi();
//       const { data } = await client.listGroups();
//       const meId = await userApi.getMe().then(me => me.user_id);
//       console.log(`Me userId ${meId}`);
//       return data.map((g) => ({
//         ...g,
//         frames: g.devices.map(d => toFrame(d)),
//         membersCount: g.members?.length ?? 0,
//         description: g.description,
//         framesCount: g.devices?.length ?? 0,
//         coverImage: g.cover_art ?? "",
//         lastUpdated: new Date().toLocaleDateString(),
//         isOwner: g.owner === meId,
//       }));
//     } catch (err) {
//       console.log(err);
//       return mockGroups;
//     }
//   },
//
//   create: async (group: Omit<Group, "id">): Promise<Group> => {
//     try {
//       const client = await createUserApi();
//       const { data } = await client.createGroup({ name: group.name, cover_art: group.coverImage });
//       const created = data.group!;
//       const meId = await userApi.getMe().then(me => me.user_id);
//       return {
//         ...created,
//         frames: created.devices.map(d => toFrame(d)),
//         description: group.description,
//         framesCount: group.devices?.length ?? 0,
//         membersCount: group.members?.length ?? 0,
//         coverImage: group.coverImage ?? "",
//         lastUpdated: new Date().toLocaleDateString(),
//         isOwner: meId === created.owner,
//       } as Group;
//     } catch (err) {
//       console.error(err);
//       const newGroup: Group = {
//         ...group,
//         id: `group-${Date.now()}`,
//         membersCount: 1,
//         framesCount: 0,
//         frames: [],
//         coverImage: group.coverImage ?? "",
//         lastUpdated: new Date().toLocaleDateString(),
//       };
//       mockGroups.push(newGroup);
//       return newGroup;
//     }
//   },
//
//   delete: async (id: string): Promise<void> => {
//     try {
//       const client = await createUserApi();
//       await client.deleteGroup(id);
//     } catch (err) {
//       const index = mockGroups.findIndex((g) => g.id === id);
//       if (index !== -1) mockGroups.splice(index, 1);
//     }
//   },
//
//   removeMember: async (groupId: string, memberId: string): Promise<void> => {
//     try {
//       const client = await createUserApi();
//       await client.removeMember(groupId, memberId);
//     } catch (err) {
//       const group = mockGroups.find((g) => g.id === groupId);
//       if (group && group.members) {
//         group.membersCount -= 1;
//       }
//     }
//   },
//
//   update: async (groupId: string, update: { cover_art?: string }): Promise<void> => {
//     const client = await createUserApi();
//     await client.configureGroup(groupId, update);
//   },
//
//   join: async(shareToken: string): Promise<boolean> => {
//     const client = await createUserApi();
//     return (await client.joinGroup({share_token: shareToken})).data.ok;
//   },
//
//   inviteCode: async(groupId: string): Promise<string> => {
//     const client = await createUserApi();
//     return (await client.createShareToken(groupId)).data.share_token;
//   }
// };
//
// export const framesApi = {
//   getAll: async (): Promise<Frame[]> => {
//     try {
//       const client = await createUserApi();
//       const { data } = await client.listDevices();
//       return data.map((d) => ({
//         ...d,
//         id: d.id ?? "",
//         serialNumber: d.id ?? "",
//         batteryLevel: d.last_battery_level,
//         status: "online",
//         lastUpdated: new Date().toLocaleDateString(),
//         groupId: (d as any).group_id,
//       }));
//     } catch (err) {
//       console.error(err);
//       return mockFrames;
//     }
//   },
//
//   getUngrouped: async (): Promise<Frame[]> => {
//     const all = await framesApi.getAll();
//     return all.filter(f => !f.groupId);
//   },
//
//   register: async (frame: Omit<Frame, "id">): Promise<Frame> => {
//     try {
//       const client = await createUserApi();
//       const { data } = await client.registerDevice(frame.serialNumber ?? "", { name: frame.name });
//       const device = data.device!;
//       return {
//         ...device,
//         id: device.id ?? frame.serialNumber,
//         serialNumber: device.id ?? frame.serialNumber,
//         batteryLevel: frame.last_battery_level ?? 100,
//         status: "online",
//         lastUpdated: new Date().toLocaleDateString(),
//         groupId: frame.groupId,
//       } as Frame;
//     } catch (err) {
//       console.error(err);
//       const newFrame: Frame = {
//         ...frame,
//         id: `frame-${Date.now()}`,
//         batteryLevel: frame.batteryLevel ?? 100,
//         lastUpdated: new Date().toLocaleDateString(),
//         status: "online",
//       };
//       mockFrames.push(newFrame);
//       return newFrame;
//     }
//   },
//
//   unregister: async (id: string): Promise<void> => {
//     try {
//       const client = await createUserApi();
//       await client.unregisterDevice(id);
//     } catch (err) {
//       console.error(err);
//       const index = mockFrames.findIndex((f) => f.id === id);
//       if (index !== -1) mockFrames.splice(index, 1);
//     }
//   },
//
//   addToGroup: async (frameId: string, groupId: string): Promise<void> => {
//     try {
//       const client = await createUserApi();
//       await client.addDeviceToGroup(groupId, frameId);
//     } catch (err) {
//       console.error(err);
//       const frame = mockFrames.find((f) => f.id === frameId);
//       if (frame) frame.groupId = groupId;
//     }
//   },
//
// };
//
// export const photosApi = {
//   getAlbums: async (): Promise<PhotoAlbum[]> => {
//     return mockPhotoAlbums;
//   },
// };
//
// export const userApi = {
//   getMe: async (): Promise<GetMeResponse> => {
//     return createUserApi()
//     .then(api => {
//       return api.getMe();
//     }).then(meResponse => {
//       return meResponse.data;
//     }).catch(e => {
//       console.error(e);
//       return Promise.reject(e);
//     });
//   }
// };

