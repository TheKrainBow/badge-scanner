// Typed fetch client: attaches the client-id/secret headers on every
// request and the session cookie via credentials: "include", matching the
// two auth layers the backend enforces.
import type {
  Account,
  AppSettings,
  CADirectoryInfo,
  ClusterData,
  ClusterOccupant,
  ScanOutcome,
  ScanRecord,
  UserDetail,
  UserRow,
} from "./types";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";
const CLIENT_ID = import.meta.env.VITE_API_CLIENT_ID ?? "";
const CLIENT_SECRET = import.meta.env.VITE_API_CLIENT_SECRET ?? "";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "X-Client-Id": CLIENT_ID,
      "X-Client-Secret": CLIENT_SECRET,
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      if (body?.error) message = body.error;
    } catch {
      // ignore non-JSON error bodies
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

function get<T>(path: string): Promise<T> {
  return request<T>(path);
}
function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: "POST", body: body !== undefined ? JSON.stringify(body) : undefined });
}
function patch<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: "PATCH", body: body !== undefined ? JSON.stringify(body) : undefined });
}
function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, { method: "PUT", body: JSON.stringify(body) });
}
function del<T>(path: string): Promise<T> {
  return request<T>(path, { method: "DELETE" });
}

export const api = {
  login: (username: string, password: string) => post<Account>("/api/auth/login", { username, password }),
  logout: () => post<{ ok: boolean }>("/api/auth/logout"),
  me: () => get<Account>("/api/auth/me"),

  scan: (uidHex: string) => post<ScanOutcome>("/api/scan", { uidHex }),
  associateBadge: (uidHex: string, login: string) => post<ScanRecord>("/api/badges/associate", { uidHex, login }),

  listHistory: (limit = 200, offset = 0) => get<ScanRecord[]>(`/api/history?limit=${limit}&offset=${offset}`),
  patchHistory: (
    id: number,
    patchBody: { reason?: string; blameStatus?: string; tigDuration?: string },
  ) => patch<ScanRecord>(`/api/history/${id}`, patchBody),
  deleteHistory: (id: number) => del<{ ok: boolean }>(`/api/history/${id}`),
  clearHistory: () => del<{ ok: boolean }>("/api/history"),

  listUsers: (params: {
    query?: string;
    type?: string;
    scannedOnly?: boolean;
    errorOnly?: boolean;
    coalition?: string;
    order?: string;
  }) => {
    const qs = new URLSearchParams();
    if (params.query) qs.set("query", params.query);
    if (params.type) qs.set("type", params.type);
    if (params.scannedOnly) qs.set("scannedOnly", "true");
    if (params.errorOnly) qs.set("errorOnly", "true");
    if (params.coalition) qs.set("coalition", params.coalition);
    if (params.order) qs.set("order", params.order);
    return get<{ rows: UserRow[] | null; coalitions: string[] | null }>(`/api/users?${qs.toString()}`);
  },
  getUser: (pk: number) => get<UserDetail>(`/api/users/${pk}`),
  deleteUser: (pk: number) => del<{ ok: boolean }>(`/api/users/${pk}`),
  refreshUserProfile: (pk: number) => post<UserDetail>(`/api/users/${pk}/refresh-profile`),
  refreshUserCoalition: (pk: number) => post<UserDetail>(`/api/users/${pk}/refresh-coalition`),
  addManualBlame: (pk: number) => post<ScanRecord>(`/api/users/${pk}/manual-blame`),

  giveCoalitionPoints: (pk: number, value: number, reason: string) =>
    post<{ message: string }>("/api/coalitions/score", { pk, value, reason }),
  giveTig: (pk: number, durationSeconds: number, reason: string) =>
    post<{ message: string }>("/api/tig", { pk, durationSeconds, reason }),

  getClusters: (force = false) => get<ClusterData>(`/api/clusters?force=${force}`),
  refreshOccupants: () => post<Record<string, ClusterOccupant>>("/api/clusters/refresh-occupants"),

  caInfo: () => get<CADirectoryInfo>("/api/ca/info"),
  refreshCADirectory: () => post<{ count: number }>("/api/ca/refresh"),

  getSettings: () => get<AppSettings>("/api/admin/settings"),
  putSettings: (settings: AppSettings) => put<AppSettings>("/api/admin/settings", settings),

  listAccounts: () => get<Account[]>("/api/admin/users"),
  createAccount: (username: string, password: string, isAdmin: boolean) =>
    post<Account>("/api/admin/users", { username, password, isAdmin }),
  deleteAccount: (id: number) => del<{ ok: boolean }>(`/api/admin/users/${id}`),
  patchAccount: (id: number, patchBody: { password?: string; isAdmin?: boolean }) =>
    patch<Account>(`/api/admin/users/${id}`, patchBody),
};
