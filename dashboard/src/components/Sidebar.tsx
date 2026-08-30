"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, SparkIcon, type IconName } from "./icons";

const NAV: { section: string; items: { href: string; label: string; icon: IconName }[] }[] = [
  {
    section: "Queries",
    items: [{ href: "/", label: "Query Console", icon: "search" }],
  },
  {
    section: "System",
    items: [
      { href: "/tenants", label: "Tenant Overview", icon: "layers" },
      { href: "/review", label: "Needs-Review Queue", icon: "flag" },
      { href: "/health", label: "System Health", icon: "activity" },
      { href: "/documents", label: "Document Library", icon: "library" },
      { href: "/upload", label: "Data Ingestion", icon: "upload" },
    ],
  },
  {
    section: "Audit",
    items: [{ href: "/audit", label: "Enterprise Audit Suite", icon: "shield" }],
  },
];

export default function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <div className="sidebar-mark">
          <SparkIcon size={17} />
        </div>
        <div>
          <div className="sidebar-logo">Campus Brain</div>
          <div className="sidebar-logo-sub">admin console v0.1</div>
        </div>
      </div>

      <nav className="nav-scroll">
        {NAV.map((group) => (
          <div key={group.section} className="nav-section">
            <div className="nav-label">{group.section}</div>
            {group.items.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className={`nav-item ${pathname === item.href ? "active" : ""}`}
              >
                <Icon name={item.icon} size={17} />
                {item.label}
              </Link>
            ))}
          </div>
        ))}
      </nav>

      <div className="sidebar-footer">
        <span
          className="strip-dot strip-dot-pass"
          style={{ width: 6, height: 6 }}
          aria-hidden
        />
        Multi-tenant RAG · local-first
      </div>
    </aside>
  );
}
