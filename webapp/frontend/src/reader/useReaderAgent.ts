import { useEffect, useRef, useState } from "react";

export type ReaderStatus = "connecting" | "connected" | "disconnected";

const READER_AGENT_URL = import.meta.env.VITE_READER_AGENT_URL ?? "ws://127.0.0.1:17420/ws";

/**
 * Connects to the local reader-agent (see webapp/reader-agent) over a
 * loopback WebSocket and calls onTag with each badge UID hex it reports.
 * Reconnects automatically if the agent isn't running yet or drops.
 *
 * Note: if this page is served over HTTPS, browsers block a plain ws://
 * connection as mixed content — see webapp/README.md for workarounds
 * (serve the frontend over plain HTTP on the local network, which is the
 * common setup for this kind of internal tool).
 */
export function useReaderAgent(onTag: (uidHex: string) => void) {
  const [status, setStatus] = useState<ReaderStatus>("connecting");
  const onTagRef = useRef(onTag);
  onTagRef.current = onTag;

  useEffect(() => {
    let socket: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;

    function connect() {
      if (stopped) return;
      setStatus("connecting");
      socket = new WebSocket(READER_AGENT_URL);

      socket.onopen = () => setStatus("connected");
      socket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.uidHex) onTagRef.current(data.uidHex);
        } catch {
          // ignore malformed messages
        }
      };
      socket.onclose = () => {
        setStatus("disconnected");
        if (!stopped) reconnectTimer = setTimeout(connect, 2000);
      };
      socket.onerror = () => socket?.close();
    }

    connect();
    return () => {
      stopped = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, []);

  return status;
}
