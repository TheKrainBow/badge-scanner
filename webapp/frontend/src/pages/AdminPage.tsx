import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { Account, AppSettings, CADirectoryInfo } from "../api/types";

export function AdminPage() {
  return (
    <div>
      <h1>Admin</h1>
      <SettingsSection />
      <CADirectorySection />
      <UsersSection />
    </div>
  );
}

function SettingsSection() {
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.getSettings().then(setSettings);
  }, []);

  async function save() {
    if (!settings) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      setSettings(await api.putSettings(settings));
      setMessage("Settings saved");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save settings");
    } finally {
      setBusy(false);
    }
  }

  if (!settings) return <p className="muted">Loading settings…</p>;

  const currentSettings = settings;
  type StringField = Exclude<keyof AppSettings, "displayDetailedScans">;

  function field(key: StringField, label: string, type = "text") {
    return (
      <div style={{ marginBottom: 10 }}>
        <label style={{ display: "block", marginBottom: 4 }}>{label}</label>
        <input
          type={type}
          style={{ width: "100%" }}
          value={currentSettings[key]}
          onChange={(e) => {
            const value = e.target.value;
            setSettings((prev) => (prev ? { ...prev, [key]: value } : prev));
          }}
        />
      </div>
    );
  }

  return (
    <div className="card">
      <h3>CA / 42 settings</h3>
      {error && <div className="error-box">{error}</div>}
      {message && <div className="success-box">{message}</div>}
      {field("caEndpoint", "CA endpoint")}
      {field("caUsername", "CA username")}
      {field("caPassword", "CA password", "password")}
      {field("ftTokenUrl", "42 OAuth token URL")}
      {field("ftEndpoint", "42 API endpoint")}
      {field("ftUid", "42 API client id")}
      {field("ftSecret", "42 API client secret", "password")}
      {field("closerId", "Closer ID (for TIGs)")}
      {field("campusId", "Campus ID")}
      <button className="btn" onClick={save} disabled={busy}>
        Save
      </button>
    </div>
  );
}

function CADirectorySection() {
  const [info, setInfo] = useState<CADirectoryInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  function reload() {
    api.caInfo().then(setInfo);
  }

  useEffect(reload, []);

  async function refresh() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await api.refreshCADirectory();
      setMessage(`Fetched ${res.count} users from the CA`);
      reload();
    } catch (err) {
      setMessage(err instanceof ApiError ? err.message : "CA refresh failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <h3>CA directory</h3>
      <p className="muted">
        {info ? (
          <>
            {info.userCount} users cached
            {info.fetchedAt ? `, last fetched ${new Date(info.fetchedAt).toLocaleString()}` : " (never fetched)"}
          </>
        ) : (
          "Loading…"
        )}
      </p>
      {message && <div className="success-box">{message}</div>}
      <button className="btn" onClick={refresh} disabled={busy}>
        {busy ? "Refetching…" : "Refetch CA users"}
      </button>
      <p className="muted" style={{ marginTop: 8 }}>
        Slow — only run when badges are missing or changed. This also clears any manual badge-to-student links.
      </p>
    </div>
  );
}

function UsersSection() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [isAdmin, setIsAdmin] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function reload() {
    api.listAccounts().then(setAccounts);
  }

  useEffect(reload, []);

  async function create() {
    setError(null);
    try {
      await api.createAccount(username, password, isAdmin);
      setUsername("");
      setPassword("");
      setIsAdmin(false);
      reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to create account");
    }
  }

  async function remove(id: number) {
    if (!confirm("Delete this account?")) return;
    await api.deleteAccount(id);
    reload();
  }

  async function resetPassword(id: number) {
    const newPassword = prompt("New password (min 8 characters):");
    if (!newPassword) return;
    await api.patchAccount(id, { password: newPassword });
    alert("Password updated");
  }

  async function toggleAdmin(account: Account) {
    await api.patchAccount(account.id, { isAdmin: !account.isAdmin });
    reload();
  }

  return (
    <div className="card">
      <h3>Accounts</h3>
      <table>
        <thead>
          <tr>
            <th>Username</th>
            <th>Admin</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((a) => (
            <tr key={a.id}>
              <td>{a.username}</td>
              <td>
                <input type="checkbox" checked={a.isAdmin} onChange={() => toggleAdmin(a)} />
              </td>
              <td>
                <button className="btn secondary" onClick={() => resetPassword(a.id)}>
                  Reset password
                </button>{" "}
                <button className="btn danger" onClick={() => remove(a.id)}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h4>New account</h4>
      {error && <div className="error-box">{error}</div>}
      <div className="row">
        <input placeholder="Username" value={username} onChange={(e) => setUsername(e.target.value)} />
        <input
          placeholder="Password (min 8 chars)"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <label>
          <input type="checkbox" checked={isAdmin} onChange={(e) => setIsAdmin(e.target.checked)} /> Admin
        </label>
        <button className="btn" onClick={create}>
          Create
        </button>
      </div>
    </div>
  );
}
