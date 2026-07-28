import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { BlameStatus, ScanRecord } from "../api/types";

const STATUS_LABEL: Record<BlameStatus, string> = {
  NOT_HANDLED: "To handle",
  PARDONED: "Pardoned",
  TIGED: "TIGed",
};

export function HistoryPage() {
  const [records, setRecords] = useState<ScanRecord[]>([]);
  const [loading, setLoading] = useState(true);

  function reload() {
    setLoading(true);
    api
      .listHistory()
      .then(setRecords)
      .finally(() => setLoading(false));
  }

  useEffect(reload, []);

  async function setStatus(r: ScanRecord, status: BlameStatus) {
    const updated = await api.patchHistory(r.id, { blameStatus: status });
    setRecords((prev) => prev.map((x) => (x.id === r.id ? updated : x)));
  }

  async function remove(r: ScanRecord) {
    await api.deleteHistory(r.id);
    setRecords((prev) => prev.filter((x) => x.id !== r.id));
  }

  async function clearAll() {
    if (!confirm("Clear the whole scan history?")) return;
    await api.clearHistory();
    setRecords([]);
  }

  return (
    <div>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>History</h1>
        <button className="btn danger" onClick={clearAll}>
          Clear history
        </button>
      </div>

      {loading ? (
        <p className="muted">Loading…</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>User</th>
              <th>Badge</th>
              <th>Blame</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {records.map((r) => (
              <tr key={r.id}>
                <td className="muted">{new Date(r.timestamp).toLocaleString()}</td>
                <td>
                  <div className="row">
                    {r.photoUrl && <img className="avatar" style={{ width: 28, height: 28 }} src={r.photoUrl} alt="" />}
                    <span>{r.login ?? <span className="muted">{r.error ?? "unknown"}</span>}</span>
                  </div>
                </td>
                <td className="muted">{r.wiegand}</td>
                <td>
                  {r.isBlame ? (
                    <select value={r.blameStatus} onChange={(e) => setStatus(r, e.target.value as BlameStatus)}>
                      {(Object.keys(STATUS_LABEL) as BlameStatus[]).map((s) => (
                        <option key={s} value={s}>
                          {STATUS_LABEL[s]}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="muted">scan only</span>
                  )}
                </td>
                <td>
                  <button className="btn secondary" onClick={() => remove(r)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
