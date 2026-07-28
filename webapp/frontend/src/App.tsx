import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { Nav } from "./components/Nav";
import { LoginPage } from "./pages/LoginPage";
import { ScanPage } from "./pages/ScanPage";
import { HistoryPage } from "./pages/HistoryPage";
import { UsersPage } from "./pages/UsersPage";
import { UserDetailPage } from "./pages/UserDetailPage";
import { AssociatePage } from "./pages/AssociatePage";
import { ClustersPage } from "./pages/ClustersPage";
import { AdminPage } from "./pages/AdminPage";

export default function App() {
  const { user, loading } = useAuth();

  if (loading) return null;

  if (!user) {
    return (
      <Routes>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    );
  }

  return (
    <div className="app-shell">
      <Nav />
      <div className="main">
        <Routes>
          <Route path="/scan" element={<ScanPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/users" element={<UsersPage />} />
          <Route path="/users/:pk" element={<UserDetailPage />} />
          <Route path="/associate/:uidHex" element={<AssociatePage />} />
          <Route path="/clusters" element={<ClustersPage />} />
          {user.isAdmin && <Route path="/admin" element={<AdminPage />} />}
          <Route path="*" element={<Navigate to="/scan" replace />} />
        </Routes>
      </div>
    </div>
  );
}
