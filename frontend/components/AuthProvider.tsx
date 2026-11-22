"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { Loading } from "@/components/Feedback";
import { Logo } from "@/components/Logo";
import { UsageGuidePanel, useGuideOpen } from "@/components/UsageGuidePanel";
import { IconAlert, IconBook, IconChart, IconDashboard, IconLayers, IconReport, IconTarget, IconGlobe, IconLoan, IconPiggy, IconCog, IconHome, IconKey, IconLogout, IconMenu, IconMoon, IconShield, IconSun, IconTransfer, IconUsers } from "@/components/Icons";
import { authApi, UNAUTHENTICATED_EVENT } from "@/lib/api";
import { clearCache } from "@/lib/query";
import type { LoginUser, Role } from "@/lib/types";

interface AuthState {
  user: LoginUser | null;
  setUser: (user: LoginUser | null) => void;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth outside AuthProvider");
  return ctx;
}

export const roleLabel: Record<Role, string> = { CUSTOMER: "お客さま", TELLER: "窓口担当", SUPERVISOR: "役席" };

const PUBLIC_PATHS = ["/login"];

/**
 * Login state for the whole app. The session itself is an HttpOnly cookie set by the API; this only mirrors
 * who is logged in (GET /me) to choose navigation and redirect to the login / password-change screens.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [user, setUser] = useState<LoginUser | null>(null);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    authApi
      .me()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setChecked(true));
    const onExpired = () => {
      clearCache();
      setUser(null);
    };
    window.addEventListener(UNAUTHENTICATED_EVENT, onExpired);
    return () => window.removeEventListener(UNAUTHENTICATED_EVENT, onExpired);
  }, []);

  const isPublic = PUBLIC_PATHS.includes(pathname);
  useEffect(() => {
    if (!checked) return;
    if (!user && !isPublic) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    else if (user?.mustChangePassword && pathname !== "/password") router.replace("/password");
  }, [checked, user, isPublic, pathname, router]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setUser(null);
      router.replace("/login");
    }
  }, [router]);

  const ready = checked && (isPublic || (user && (!user.mustChangePassword || pathname === "/password")));
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => setMenuOpen(false), [pathname]);
  const [guideOpen, setGuideOpen] = useGuideOpen();

  // Login and other public pages use their own full-screen layout
  if (isPublic) {
    return <AuthContext.Provider value={{ user, setUser, logout }}>{ready ? children : <Loading />}</AuthContext.Provider>;
  }

  return (
    <AuthContext.Provider value={{ user, setUser, logout }}>
      <div className="shell">
        <aside className="sidebar" data-open={menuOpen} aria-label="メインメニュー">
          <Brand />
          {user && !user.mustChangePassword && <Nav user={user} pathname={pathname} />}
          {user && (
            <div className="sidebar-foot">
              <button type="button" className="ghost-btn guide-btn" aria-expanded={guideOpen}
                onClick={() => setGuideOpen(!guideOpen)}>
                <IconBook size={16} /> 利用手順
              </button>
              <div className="user-card">
                <span className="avatar" aria-hidden="true">{user.displayName.slice(0, 1)}</span>
                <div className="who">
                  <strong>{user.displayName}</strong>
                  <span>
                    {roleLabel[user.role]} ・ {user.userId}
                  </span>
                </div>
              </div>
              <div className="sidebar-actions">
                <ThemeToggle />
                <Link className="ghost-btn" href="/password" title="パスワード変更">
                  <IconKey size={16} /> パスワード
                </Link>
                <button type="button" className="ghost-btn" onClick={logout}>
                  <IconLogout size={16} /> ログアウト
                </button>
              </div>
            </div>
          )}
        </aside>
        {menuOpen && <div className="scrim" onClick={() => setMenuOpen(false)} aria-hidden="true" />}

        <div className="content">
          <div className="topbar">
            <button type="button" className="icon-btn" aria-label="メニューを開く" aria-expanded={menuOpen}
              onClick={() => setMenuOpen(true)}>
              <IconMenu />
            </button>
            <Brand compact />
            <button type="button" className="icon-btn" aria-label="利用手順" title="利用手順" aria-expanded={guideOpen}
              onClick={() => setGuideOpen(!guideOpen)}>
              <IconBook size={18} />
            </button>
            <ThemeToggle />
          </div>
          <main className="container">{ready ? children : <Loading />}</main>
          <footer className="footer">デモ用サンプル（架空データ）。本番利用不可。</footer>
        </div>
      </div>
      {user && <UsageGuidePanel open={guideOpen} onClose={() => setGuideOpen(false)} />}
    </AuthContext.Provider>
  );
}

function Brand({ compact }: { compact?: boolean }) {
  return (
    <Link href="/" className="brand">
      <Logo size={compact ? 30 : 36} />
      <span>
        永劫銀行
        {!compact && <small>ONLINE BANKING</small>}
      </span>
    </Link>
  );
}

function Nav({ user, pathname }: { user: LoginUser; pathname: string }) {
  const item = (href: string, label: string, icon: React.ReactNode) => {
    const active = href === "/" ? pathname === "/" || pathname.startsWith("/accounts") : pathname.startsWith(href);
    return (
      <Link href={href} aria-current={active ? "page" : undefined}>
        {icon}
        {label}
      </Link>
    );
  };
  return (
    <nav className="nav">
      <div className="nav-section">BANKING</div>
      {item("/", user.customer ? "ご利用口座" : "口座一覧", <IconHome />)}
      {item("/transfer", "振込・振替", <IconTransfer />)}
      {item("/payments", "為替・口座振替", <IconGlobe />)}
      {item("/deposits", "預金商品", <IconPiggy />)}
      {item("/loans", "融資", <IconLoan />)}
      {user.staff && (
        <>
          <div className="nav-section">BRANCH</div>
          {item("/customers", "顧客・口座開設", <IconUsers />)}
        </>
      )}
      {user.staff && (
        <>
          <div className="nav-section">INFORMATION</div>
          {user.role === "SUPERVISOR" && item("/mis", "経営ダッシュボード", <IconDashboard />)}
          {item("/analytics", "顧客分析", <IconTarget />)}
          {user.role === "SUPERVISOR" && item("/reports", "帳票", <IconReport />)}
        </>
      )}
      {user.role === "SUPERVISOR" && (
        <>
          <div className="nav-section">ADMIN</div>
          {item("/risk", "リスク管理", <IconChart />)}
          {item("/aml", "AML", <IconAlert />)}
          {item("/operations", "業務処理", <IconCog />)}
          {item("/batch", "銀行バッチ", <IconLayers />)}
          {item("/audit", "監査ログ", <IconShield />)}
        </>
      )}
    </nav>
  );
}

/** Light / dark switch. Follows the OS until the user picks; the choice is remembered on this device only. */
export function ThemeToggle() {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    let saved: string | null = null;
    try {
      saved = localStorage.getItem("theme");
    } catch {
      // storage unavailable (private mode etc.): just follow the OS
    }
    const isDark = saved ? saved === "dark" : window.matchMedia("(prefers-color-scheme: dark)").matches;
    if (saved) document.documentElement.dataset.theme = saved;
    setDark(isDark);
  }, []);
  const toggle = () => {
    const next = dark ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem("theme", next);
    } catch {
      // not persisted; still applies for this page view
    }
    setDark(!dark);
  };
  return (
    <button type="button" className="icon-btn" onClick={toggle} aria-label={dark ? "ライトモードにする" : "ダークモードにする"}
      title={dark ? "ライトモード" : "ダークモード"}>
      {dark ? <IconSun size={18} /> : <IconMoon size={18} />}
    </button>
  );
}

/** Page guard: shows a message instead of the page for other roles (the API enforces the same rule). */
export function RequireRole({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { user } = useAuth();
  if (!user || !roles.includes(user.role)) {
    return (
      <div className="alert alert-error" role="alert">
        この画面を利用する権限がありません。
      </div>
    );
  }
  return <>{children}</>;
}
