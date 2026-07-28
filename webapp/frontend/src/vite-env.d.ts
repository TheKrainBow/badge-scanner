/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE: string;
  readonly VITE_API_CLIENT_ID: string;
  readonly VITE_API_CLIENT_SECRET: string;
  readonly VITE_READER_AGENT_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
