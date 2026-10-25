import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { NavigationEnd, RouterModule, Router } from '@angular/router';
import { BreakpointObserver } from '@angular/cdk/layout';
import { filter, map, startWith } from 'rxjs/operators';
import { combineLatest } from 'rxjs';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Store } from '@ngrx/store';
import { selectCurrentUser, selectUserRoles } from './store/auth/auth.selectors';
import { selectSelectedWorkspaceId, selectWorkspaces } from './store/workspace/workspace.selectors';
import { authActions } from './store/auth/auth.actions';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  roles?: string[];
}

interface NavSection {
  title: string;
  items: NavItem[];
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, RouterModule, MatSidenavModule, MatIconModule, MatButtonModule,
    MatMenuModule, MatDividerModule, MatTooltipModule,
  ],
  template: `
    <router-outlet *ngIf="!showShell()"></router-outlet>
    <mat-sidenav-container *ngIf="showShell()" class="shell" [class.collapsed]="collapsed() && !isMobile()">
      <mat-sidenav #sidenav class="sidebar"
                   [mode]="isMobile() ? 'over' : 'side'"
                   [opened]="!isMobile() || mobileOpen()"
                   (closedStart)="mobileOpen.set(false)">
        <div class="brand">
          <img class="brand-logo" src="brand/pi-logo.png" alt="PI Security Copilot logo" />
          <span class="brand-name">PI Security Copilot</span>
          <button class="icon-btn collapse-btn" type="button" (click)="toggleSidebar()"
                  [attr.aria-label]="isMobile() ? 'Close menu' : collapsed() ? 'Expand sidebar' : 'Collapse sidebar'">
            <mat-icon>{{ isMobile() ? 'close' : collapsed() ? 'keyboard_double_arrow_right' : 'keyboard_double_arrow_left' }}</mat-icon>
          </button>
        </div>

        <nav class="nav">
          <ng-container *ngFor="let section of visibleSections$ | async">
            <div class="nav-section">{{ section.title }}</div>
            <a *ngFor="let item of section.items" class="nav-link" [routerLink]="item.route"
               routerLinkActive="active" (click)="onNavigate()"
               [matTooltip]="item.label" matTooltipPosition="right"
               [matTooltipDisabled]="!collapsed() || isMobile()">
              <span class="nav-icon"><mat-icon>{{ item.icon }}</mat-icon></span>
              <span class="nav-label">{{ item.label }}</span>
            </a>
          </ng-container>
        </nav>

        <div class="sidebar-footer">
          <div class="promo">
            <div class="promo-title">Protect PI faster</div>
            <p class="promo-text">Connect a repo and database to start your first discovery scan.</p>
            <a class="promo-btn" routerLink="/scans/new" (click)="onNavigate()">
              <mat-icon>radar</mat-icon> Start a Scan
            </a>
          </div>
          <a class="nav-link" (click)="logout()" matTooltip="Sign out" matTooltipPosition="right"
             [matTooltipDisabled]="!collapsed() || isMobile()">
            <span class="nav-icon"><mat-icon>logout</mat-icon></span>
            <span class="nav-label">Sign Out</span>
          </a>
        </div>
      </mat-sidenav>

      <mat-sidenav-content class="content">
        <header class="topbar">
          <button class="icon-btn menu-btn" type="button" (click)="toggleSidebar()" aria-label="Open menu">
            <mat-icon>menu</mat-icon>
          </button>

          <label class="search">
            <mat-icon>search</mat-icon>
            <input type="search" placeholder="Search findings, tables, columns…"
                   (keyup.enter)="search($any($event.target).value)" />
          </label>

          <span class="spacer"></span>

          <button class="workspace-chip" type="button" routerLink="/workspaces" matTooltip="Manage workspaces">
            <mat-icon>workspaces</mat-icon>
            <span class="ws-name">{{ (workspaceName$ | async) || 'Workspace' }}</span>
          </button>

          <button class="icon-btn" type="button" routerLink="/audit" aria-label="Activity" matTooltip="Activity">
            <mat-icon>history</mat-icon>
          </button>
          <button class="icon-btn" type="button" aria-label="Notifications" matTooltip="Notifications">
            <mat-icon>notifications_none</mat-icon>
            <span class="dot"></span>
          </button>

          <span class="divider-v"></span>

          <button class="user" type="button" [matMenuTriggerFor]="userMenu">
            <span class="avatar">{{ initials((currentUser$ | async)?.name) }}</span>
            <span class="user-meta">
              <span class="user-name">{{ (currentUser$ | async)?.name || 'User' }}</span>
              <span class="user-role">{{ formatRole((currentUser$ | async)?.roles?.[0]) }}</span>
            </span>
            <mat-icon class="chevron">expand_more</mat-icon>
          </button>
          <mat-menu #userMenu="matMenu" xPosition="before">
            <div class="menu-header">
              <div class="user-name">{{ (currentUser$ | async)?.name || 'User' }}</div>
              <div class="user-role">{{ (currentUser$ | async)?.email }}</div>
            </div>
            <mat-divider></mat-divider>
            <button mat-menu-item routerLink="/workspaces">
              <mat-icon>swap_horiz</mat-icon> Switch Workspace
            </button>
            <button mat-menu-item (click)="logout()">
              <mat-icon>logout</mat-icon> Sign Out
            </button>
          </mat-menu>
        </header>

        <main class="main">
          <router-outlet></router-outlet>
        </main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: [`
    :host { display: block; }
    .shell { height: 100vh; background: var(--pc-bg); }

    /* Sidebar */
    .sidebar {
      width: var(--pc-sidebar-width);
      background: var(--pc-surface);
      border-right: 1px solid var(--pc-border) !important;
      transition: width 0.2s ease;
    }
    .sidebar ::ng-deep .mat-drawer-inner-container {
      display: flex; flex-direction: column; overflow-x: hidden;
    }
    .collapsed .sidebar { width: var(--pc-sidebar-collapsed); }
    .collapsed .nav-label, .collapsed .brand-name, .collapsed .nav-section,
    .collapsed .promo { display: none; }
    .collapsed .brand { justify-content: center; padding: 20px 0; }
    .collapsed .brand-logo { display: none; }
    .collapsed .nav-link { justify-content: center; padding: 10px 0; }

    .brand {
      display: flex; align-items: center; gap: 10px;
      padding: 20px 16px 20px 20px;
    }
    .brand-logo {
      width: 40px; height: 40px; border-radius: 10px; flex-shrink: 0;
      object-fit: cover;
      box-shadow: 0 2px 8px rgba(14, 116, 200, 0.35);
    }
    .brand-name { font-size: 16px; font-weight: 700; color: var(--pc-text); flex: 1; white-space: nowrap; }

    .nav { flex: 1; padding: 4px 12px; overflow-y: auto; }
    .nav-section {
      padding: 16px 12px 6px;
      font-size: 11px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase;
      color: var(--pc-text-subtle);
    }
    .nav-link {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 12px; margin: 2px 0;
      border-radius: var(--pc-radius-sm);
      color: #4b5563; text-decoration: none; font-weight: 500; cursor: pointer;
      white-space: nowrap;
      transition: background 0.15s, color 0.15s;
    }
    .nav-link:hover { background: #f6f5ff; color: var(--pc-primary); }
    .nav-link.active { background: var(--pc-primary-soft); color: var(--pc-primary); font-weight: 600; }
    .nav-icon { display: grid; place-items: center; width: 24px; height: 24px; }
    .nav-link.active .nav-icon {
      background: var(--pc-primary); color: #fff; border-radius: 7px;
    }
    .nav-icon mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .sidebar-footer { padding: 12px; }
    .promo {
      padding: 18px; margin-bottom: 8px; border-radius: var(--pc-radius);
      background: linear-gradient(145deg, #7c5cff 0%, #5b3df5 60%, #4527cc 100%);
      color: #fff;
    }
    .promo-title { font-size: 15px; font-weight: 600; }
    .promo-text { margin: 6px 0 14px; font-size: 12.5px; line-height: 1.5; opacity: 0.9; }
    .promo-btn {
      display: flex; align-items: center; justify-content: center; gap: 6px;
      padding: 9px; border-radius: var(--pc-radius-sm);
      background: #fff; color: var(--pc-primary); font-weight: 600; text-decoration: none;
    }
    .promo-btn mat-icon { font-size: 18px; width: 18px; height: 18px; }

    /* Top bar */
    .content { display: flex; flex-direction: column; }
    .topbar {
      position: sticky; top: 0; z-index: 10;
      display: flex; align-items: center; gap: 8px;
      height: var(--pc-header-height); padding: 0 var(--pc-gutter);
      background: rgba(255, 255, 255, 0.92);
      backdrop-filter: blur(8px);
      border-bottom: 1px solid var(--pc-border);
    }
    .icon-btn {
      position: relative; display: grid; place-items: center;
      width: 40px; height: 40px; border: none; border-radius: var(--pc-radius-sm);
      background: transparent; color: #4b5563; cursor: pointer;
    }
    .icon-btn:hover { background: #f3f4f6; color: var(--pc-text); }
    .dot {
      position: absolute; top: 9px; right: 10px;
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--pc-danger); border: 2px solid #fff;
    }
    .search {
      display: flex; align-items: center; gap: 8px;
      width: min(420px, 100%); height: 40px; padding: 0 12px;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm);
      background: #f9fafb; color: var(--pc-text-subtle);
    }
    .search:focus-within { border-color: var(--pc-primary); background: #fff; }
    .search mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .search input {
      flex: 1; min-width: 0; border: none; outline: none; background: transparent;
      font: inherit; color: var(--pc-text);
    }
    .menu-btn { display: none; }
    .spacer { flex: 1; }
    .workspace-chip {
      display: flex; align-items: center; gap: 6px; max-width: 200px;
      height: 36px; padding: 0 12px; border: 1px solid var(--pc-border); border-radius: 999px;
      background: #fff; color: var(--pc-text); font: inherit; font-weight: 500; cursor: pointer;
    }
    .workspace-chip:hover { border-color: var(--pc-primary); color: var(--pc-primary); }
    .workspace-chip mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--pc-primary); }
    .ws-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .divider-v { width: 1px; height: 28px; background: var(--pc-border); margin: 0 6px; }
    .user {
      display: flex; align-items: center; gap: 10px;
      padding: 4px 6px; border: none; border-radius: var(--pc-radius-sm);
      background: transparent; cursor: pointer; font: inherit; text-align: left;
    }
    .user:hover { background: #f3f4f6; }
    .avatar {
      display: grid; place-items: center; width: 36px; height: 36px; border-radius: 50%;
      background: var(--pc-primary-soft); color: var(--pc-primary); font-weight: 600; font-size: 13px;
    }
    .user-meta { display: flex; flex-direction: column; line-height: 1.25; }
    .user-name { font-weight: 600; color: var(--pc-text); font-size: 13px; }
    .user-role { color: var(--pc-text-muted); font-size: 12px; }
    .chevron { color: var(--pc-text-muted); }
    .menu-header { padding: 12px 16px; }

    .main {
      flex: 1;
      width: 100%; max-width: 1440px; margin: 0 auto;
      padding: var(--pc-gutter);
    }

    @media (max-height: 820px) {
      .promo { display: none; }
    }
    @media (max-width: 960px) {
      .menu-btn { display: grid; }
    }
    @media (max-width: 1024px) {
      .workspace-chip .ws-name, .user-meta, .chevron { display: none; }
    }
    @media (max-width: 600px) {
      .search { display: none; }
      .workspace-chip, .divider-v { display: none; }
    }
  `],
})
export class AppComponent {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly breakpoints = inject(BreakpointObserver);

  readonly currentUser$ = this.store.select(selectCurrentUser);
  readonly workspaceName$ = combineLatest([
    this.store.select(selectWorkspaces),
    this.store.select(selectSelectedWorkspaceId),
  ]).pipe(map(([workspaces, id]) => workspaces.find((w) => w.id === id)?.name));

  readonly collapsed = signal(false);
  readonly mobileOpen = signal(false);
  readonly isMobile = toSignal(
    this.breakpoints.observe('(max-width: 960px)').pipe(map((s) => s.matches)),
    { initialValue: false },
  );

  /** Login and workspace selection render full-screen, without the nav shell. */
  readonly showShell = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => !/^\/(login|select-workspace)(\/|\?|$)/.test(e.urlAfterRedirects)),
      startWith(false),
    ),
    { initialValue: false },
  );

  navSections: NavSection[] = [
    {
      title: 'Overview',
      items: [
        { label: 'Dashboard', icon: 'grid_view', route: '/dashboard' },
        { label: 'Workspaces', icon: 'workspaces', route: '/workspaces' },
        { label: 'Lifecycle', icon: 'route', route: '/lifecycle' },
      ],
    },
    {
      title: 'Discovery',
      items: [
        { label: 'Connectors', icon: 'hub', route: '/connectors', roles: ['APP_OWNER', 'APP_DEVELOPER', 'PLATFORM_ADMIN'] },
        { label: 'Scans', icon: 'radar', route: '/scans' },
        { label: 'Inventory', icon: 'inventory_2', route: '/inventory' },
      ],
    },
    {
      title: 'Protection',
      items: [
        { label: 'Encryption', icon: 'vpn_key', route: '/encryption', roles: ['PLATFORM_ADMIN', 'SECURITY_REVIEWER'] },
        { label: 'Backfill', icon: 'sync', route: '/backfill' },
        { label: 'Feature Flags', icon: 'toggle_on', route: '/feature-flags', roles: ['PLATFORM_ADMIN'] },
      ],
    },
    {
      title: 'Governance',
      items: [
        { label: 'Audit', icon: 'history', route: '/audit', roles: ['PRIVACY_REVIEWER', 'SECURITY_REVIEWER', 'AUDITOR', 'PLATFORM_ADMIN'] },
        { label: 'Admin', icon: 'admin_panel_settings', route: '/admin', roles: ['PLATFORM_ADMIN'] },
      ],
    },
  ];

  readonly visibleSections$ = this.store.select(selectUserRoles).pipe(
    map((roles) => this.navSections
      .map((section) => ({
        ...section,
        items: section.items.filter(
          (item) => !item.roles || item.roles.some((r) => roles.includes(r as never))
        ),
      }))
      .filter((section) => section.items.length > 0)),
  );

  toggleSidebar(): void {
    if (this.isMobile()) {
      this.mobileOpen.update((open) => !open);
    } else {
      this.collapsed.update((c) => !c);
    }
  }

  onNavigate(): void {
    if (this.isMobile()) {
      this.mobileOpen.set(false);
    }
  }

  search(term: string): void {
    this.router.navigate(['/inventory'], { queryParams: term ? { search: term } : {} });
  }

  initials(name?: string): string {
    if (!name) return 'U';
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
  }

  formatRole(role?: string): string {
    if (!role) return '';
    return role.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  }

  logout(): void {
    this.store.dispatch(authActions.logout());
  }
}
