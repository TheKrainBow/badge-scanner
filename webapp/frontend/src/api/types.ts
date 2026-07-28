export type BlameStatus = "NOT_HANDLED" | "PARDONED" | "TIGED";

export interface CADirEntry {
  pk: number;
  fullName: string;
  ftLogin: string;
  ftId: string;
  badges: number[];
}

export interface ScanRecord {
  id: number;
  timestamp: number;
  uidHex: string;
  mifareHex: string;
  wiegand: string;
  login?: string;
  ftId?: string;
  photoUrl?: string;
  error?: string;
  userType?: string;
  coalitionName?: string;
  coalitionColor?: string;
  coalitionImageUrl?: string;
  reason?: string;
  isBlame: boolean;
  blameStatus: BlameStatus;
  tigDuration?: string;
}

export interface ScanOutcome {
  status: "user" | "success" | "failure";
  record: ScanRecord;
  entry?: CADirEntry;
}

export interface UserRow {
  entry: CADirEntry;
  login: string;
  scanCount: number;
  pendingCount: number;
  lastScan?: number;
  userType: string;
  coalitionName: string;
  coalitionColor: string;
  photoUrl: string;
  hasError: boolean;
}

export interface UserDetail {
  entry: CADirEntry;
  login: string;
  ftId: string;
  photoUrl: string;
  userType: string;
  coalitionName: string;
  coalitionColor: string;
  coalitionImageUrl: string;
  coalitionId?: number;
  coalitionsUserId?: number;
  location: string;
  level?: number;
  currentProjects: string[];
  scans: ScanRecord[];
}

export interface AppSettings {
  caEndpoint: string;
  caUsername: string;
  caPassword: string;
  ftTokenUrl: string;
  ftEndpoint: string;
  ftUid: string;
  ftSecret: string;
  closerId: string;
  campusId: string;
  displayDetailedScans: boolean;
}

export interface Account {
  id: number;
  username: string;
  isAdmin: boolean;
  createdAt: number;
}

export interface ClusterInfo {
  id: number;
  name: string;
  cdnLink: string;
}

export interface ClusterSeat {
  host: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ClusterRowLabel {
  text: string;
  x: number;
  y: number;
}

export interface ClusterLayout {
  viewBoxWidth: number;
  viewBoxHeight: number;
  seats: ClusterSeat[];
  rowLabels: ClusterRowLabel[];
}

export interface ClusterOccupant {
  login: string;
  photoUrl: string;
}

export interface ClusterData {
  clusters: ClusterInfo[];
  layouts: Record<number, ClusterLayout>;
  occupants: Record<string, ClusterOccupant>;
}

export interface CADirectoryInfo {
  userCount: number;
  fetchedAt: number;
}
