import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { ScanOutcome } from "../api/types";
import { useReaderAgent } from "../reader/useReaderAgent";

export function ScanPage() {
  const navigate = useNavigate();
  const [outcome, setOutcome] = useState<ScanOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [manualUID, setManualUID] = useState("");
  const [busy, setBusy] = useState(false);

  const runScan = useCallback(
    async (uidHex: string) => {
      setBusy(true);
      setError(null);
      try {
        const result = await api.scan(uidHex);
        if (result.status === "user" && result.entry) {
          navigate(`/users/${result.entry.pk}`);
          return;
        }
        setOutcome(result);
      } catch (err) {
        setError(err instanceof ApiError ? err.message : "Scan failed");
      } finally {
        setBusy(false);
      }
    },
    [navigate],
  );

  const readerStatus = useReaderAgent((uidHex) => runScan(uidHex));

  function onManualSubmit() {
    const hex = manualUID.trim();
    if (hex) runScan(hex);
    setManualUID("");
  }

  return (
    <div>
      <h1>Scan</h1>

      <div className="card row">
        <span
          className="pill"
          style={{
            background:
              readerStatus === "connected" ? "var(--success)" : readerStatus === "connecting" ? "var(--border)" : "var(--danger)",
          }}
        >
          Reader agent: {readerStatus}
        </span>
        {readerStatus !== "connected" && (
          <span className="muted">
            Not receiving badge taps. Make sure the reader-agent is running on this machine (see webapp/README.md).
          </span>
        )}
      </div>

      <div className="card">
        <h3>Manual entry</h3>
        <p className="muted">Type or paste a badge UID hex (e.g. E01CBEDB) — useful without a physical reader.</p>
        <div className="row">
          <input
            value={manualUID}
            onChange={(e) => setManualUID(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && onManualSubmit()}
            placeholder="UID hex"
          />
          <button className="btn" onClick={onManualSubmit} disabled={busy}>
            Scan
          </button>
        </div>
      </div>

      {error && <div className="error-box">{error}</div>}

      {outcome && (
        <div className="card">
          {outcome.status === "success" ? (
            <div className="row">
              {outcome.record.photoUrl && <img className="avatar" src={outcome.record.photoUrl} alt="" />}
              <div>
                <div style={{ fontWeight: 600 }}>{outcome.record.login ?? "Unknown"}</div>
                <div className="muted">
                  UID {outcome.record.uidHex} · Wiegand {outcome.record.wiegand}
                </div>
              </div>
            </div>
          ) : (
            <div>
              <div className="error-box">{outcome.record.error ?? "Badge not recognized"}</div>
              <div className="muted" style={{ marginBottom: 8 }}>
                UID {outcome.record.uidHex} · Wiegand {outcome.record.wiegand}
              </div>
              <button className="btn secondary" onClick={() => navigate(`/associate/${outcome.record.uidHex}`)}>
                Associate to a student
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
