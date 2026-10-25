import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
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
import { workspaceActions } from './store/workspace/workspace.actions';
import { WorkspaceRefreshService } from './core/api/workspace-refresh.service';
import { stageIndex, stageTitle, LIFECYCLE_STAGES } from './features/lifecycle/lifecycle-stages';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  fragment?: string;
  roles?: string[];
  /** Lifecycle stage this item belongs to; shows its step number / done state. */
  stage?: string;
}

/** Same role restrictions as the stage pages' route guards. */
const STAGE_ROLES: Partial<Record<string, string[]>> = {
  CONNECT: ['APP_OWNER', 'APP_DEVELOPER', 'PLATFORM_ADMIN'],
  ENCRYPTION_SETUP: ['PLATFORM_ADMIN', 'SECURITY_REVIEWER'],
  ENABLE_ENCRYPTION: ['PLATFORM_ADMIN'],
};

const LIFECYCLE_TITLE = 'Workspace Lifecycle';


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
          <img class="brand-logo" src="brand/pi-logo.png" alt="PI Security Co-Pilot logo" />
          <span class="brand-name">PI Security Co-Pilot</span>
          <button class="icon-btn collapse-btn" type="button" (click)="toggleSidebar()"
                  [attr.aria-label]="isMobile() ? 'Close menu' : collapsed() ? 'Expand sidebar' : 'Collapse sidebar'">
            <mat-icon>{{ isMobile() ? 'close' : collapsed() ? 'keyboard_double_arrow_right' : 'keyboard_double_arrow_left' }}</mat-icon>
          </button>
        </div>

        <nav class="nav">
          <ng-container *ngFor="let section of visibleSections$ | async">
            <div class="nav-section">{{ section.title }}</div>
            <a *ngFor="let item of section.items" class="nav-link" [routerLink]="item.route" [fragment]="item.fragment"
               routerLinkActive="active" [routerLinkActiveOptions]="activeOptions" (click)="onNavigate()"
               [matTooltip]="item.label" matTooltipPosition="right"
               [matTooltipDisabled]="!collapsed() || isMobile()">
              <span class="nav-icon"><mat-icon>{{ item.icon }}</mat-icon></span>
              <span class="nav-label">{{ item.label }}</span>
              <span class="nav-step" *ngIf="item.stage" [ngClass]="stepState(item.stage)"
                    [attr.aria-label]="'Step ' + stepNumber(item.stage) + ', ' + stepState(item.stage)">
                {{ stepNumber(item.stage) }}
              </span>
            </a>
          </ng-container>
        </nav>

        <div class="sidebar-footer">
          <div class="progress" *ngIf="lifecycleProgress() as p" [class.rejected]="p.rejected"
               [class.complete]="p.done === p.total">
            <div class="progress-title">
              <mat-icon>{{ p.rejected ? 'block' : p.done === p.total ? 'verified' : 'route' }}</mat-icon>
              Workflow progress
            </div>
            <div class="progress-meta">
              <span>{{ p.rejected ? 'Rejected · ' : '' }}{{ p.done }} of {{ p.total }} complete</span>
              <span class="progress-pct">{{ p.percent }}%</span>
            </div>
            <div class="progress-track"><div class="progress-fill" [style.width.%]="p.percent"></div></div>
          </div>
          <a class="nav-link logout" (click)="logout()" matTooltip="Sign out" matTooltipPosition="right"
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

          <span class="spacer"></span>

          <button class="workspace-chip" type="button" routerLink="/workspaces" matTooltip="Manage workspaces">
            <mat-icon>workspaces</mat-icon>
            <span class="ws-name">{{ (workspaceName$ | async) || 'Workspace' }}</span>
          </button>
          <a class="stage-chip" routerLink="/lifecycle" *ngIf="stageLabel$ | async as stage"
             [class.done]="stage === 'Completed'" [class.rejected]="stage === 'Rejected'" matTooltip="Current lifecycle stage">
            <mat-icon>{{ stage === 'Completed' ? 'verified' : stage === 'Rejected' ? 'block' : 'route' }}</mat-icon>
            <span class="ws-name">{{ stage }}</span>
          </a>

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

    /* Sidebar: deep navy with a single cyan accent, matching the brand artwork */
    .sidebar {
      --sb-accent: #22d3ee;
      --sb-text: #cbd5e1;
      --sb-muted: #7c8db5;
      --sb-line: rgba(148, 163, 184, 0.14);
      width: var(--pc-sidebar-width);
      background:
        radial-gradient(120% 60% at 0% 0%, rgba(34, 211, 238, 0.10), transparent 60%),
        linear-gradient(180deg, #0b1733 0%, #0a1430 55%, #081026 100%);
      border-right: 1px solid rgba(34, 211, 238, 0.12) !important;
      color: var(--sb-text);
      transition: width 0.2s ease;
    }
    .sidebar ::ng-deep .mat-drawer-inner-container {
      display: flex; flex-direction: column; overflow-x: hidden;
    }
    .collapsed .sidebar { width: var(--pc-sidebar-collapsed); }
    .collapsed .nav-label, .collapsed .brand-name, .collapsed .nav-section,
    .collapsed .progress { display: none; }
    .collapsed .brand { justify-content: center; padding: 20px 0; }
    .collapsed .brand-logo { display: none; }
    .collapsed .nav-link { justify-content: center; padding: 8px 0; }

    .brand {
      display: flex; align-items: center; gap: 10px;
      padding: 20px 14px 18px 18px;
      border-bottom: 1px solid var(--sb-line);
    }
    .brand-logo {
      width: 38px; height: 38px; border-radius: 10px; flex-shrink: 0; object-fit: cover;
      box-shadow: 0 0 0 1px rgba(34, 211, 238, 0.35), 0 0 18px rgba(34, 211, 238, 0.30);
    }
    .brand-name {
      flex: 1; white-space: nowrap; font-size: 15px; font-weight: 700; letter-spacing: 0.01em;
      background: linear-gradient(90deg, #ffffff, #a5f3fc);
      -webkit-background-clip: text; background-clip: text; color: transparent;
    }
    .sidebar .collapse-btn { color: var(--sb-muted); }
    .sidebar .collapse-btn:hover { background: rgba(148, 163, 184, 0.12); color: #fff; }

    .nav { flex: 1; padding: 6px 12px 12px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: rgba(148, 163, 184, 0.25) transparent; }
    .nav-section {
      padding: 18px 12px 6px;
      font-size: 10.5px; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase;
      color: var(--sb-muted);
    }
    .nav-link {
      --nav-c: var(--sb-accent);
      position: relative;
      display: flex; align-items: center; gap: 12px;
      padding: 6px 10px; margin: 2px 0;
      border-radius: 10px; border: 1px solid transparent;
      color: var(--sb-text); text-decoration: none; font-size: 13.5px; font-weight: 500; cursor: pointer;
      white-space: nowrap;
      transition: background 0.18s, color 0.18s, border-color 0.18s;
    }
    .nav-link:hover { background: rgba(148, 163, 184, 0.08); color: #fff; }
    .nav-link.active {
      background: linear-gradient(90deg, rgba(34, 211, 238, 0.16), rgba(34, 211, 238, 0.04));
      border-color: rgba(34, 211, 238, 0.22);
      color: #fff; font-weight: 600;
    }
    /* Accent bar on the active item */
    .nav-link.active::before {
      content: ''; position: absolute; left: -12px; top: 9px; bottom: 9px; width: 3px;
      border-radius: 0 3px 3px 0; background: var(--sb-accent);
      box-shadow: 0 0 10px var(--sb-accent);
    }
    .nav-icon {
      display: grid; place-items: center; width: 30px; height: 30px; flex-shrink: 0;
      border-radius: 8px; color: #8fa3c8;
      transition: color 0.18s, background 0.18s, box-shadow 0.18s;
    }
    .nav-link:hover .nav-icon { color: #e2e8f0; }
    .nav-link.active .nav-icon {
      color: var(--sb-accent); background: rgba(34, 211, 238, 0.12);
      box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.25);
    }
    .nav-link.logout { color: #fca5a5; font-weight: 600; }
    .nav-link.logout .nav-icon { color: #f87171; }
    .nav-link.logout:hover { background: rgba(248, 113, 113, 0.12); color: #fecaca; }

    /* Workflow progress: light blue in every state */
    .progress {
      padding: 12px; margin-bottom: 10px; border-radius: 12px;
      background: linear-gradient(135deg, rgba(56, 189, 248, 0.16), rgba(34, 211, 238, 0.06));
      border: 1px solid rgba(56, 189, 248, 0.30);
    }
    .progress-title {
      display: flex; align-items: center; gap: 6px; margin-bottom: 8px;
      font-size: 12px; font-weight: 700; color: #e0f2fe;
    }
    .progress-title mat-icon { font-size: 16px; width: 16px; height: 16px; color: #7dd3fc; }
    .progress-meta {
      display: flex; justify-content: space-between;
      font-size: 11px; color: #bae6fd; margin-bottom: 6px;
    }
    .progress-pct { font-weight: 700; color: #7dd3fc; }
    .progress-track { height: 6px; border-radius: 999px; background: rgba(148, 163, 184, 0.20); overflow: hidden; }
    .progress-fill {
      height: 100%; border-radius: inherit;
      background: linear-gradient(90deg, #7dd3fc, #38bdf8, #22d3ee);
      box-shadow: 0 0 8px rgba(56, 189, 248, 0.6);
      transition: width 0.4s ease;
    }
    .nav-icon mat-icon { font-size: 19px; width: 19px; height: 19px; }
    .nav-label { flex: 1; }
    .nav-step {
      display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; flex-shrink: 0;
      border: 1.5px solid rgba(148, 163, 184, 0.35); color: var(--sb-muted); font-size: 10.5px; font-weight: 700;
    }
    .nav-step.done { background: rgba(16, 185, 129, 0.18); border-color: rgba(52, 211, 153, 0.55); color: #6ee7b7; }
    /* The step in progress pulses in the accent colour */
    .nav-step.current {
      border-color: var(--sb-accent); background: var(--sb-accent); color: #062033;
      animation: step-pulse 1.4s ease-in-out infinite;
    }
    /* Upcoming steps, including those after a rejection */
    .nav-step.upcoming, .nav-step.halted { background: rgba(245, 158, 11, 0.12); border-color: rgba(251, 191, 36, 0.55); color: #fbbf24; }
    .nav-step.rejected { border-color: #f87171; background: #ef4444; color: #fff; box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.25); }
    @keyframes step-pulse {
      0%, 100% { box-shadow: 0 0 0 0 rgba(34, 211, 238, 0.55); }
      50% { box-shadow: 0 0 0 5px rgba(34, 211, 238, 0); }
    }
    @media (prefers-reduced-motion: reduce) {
      .nav-step.current { animation: none; box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.25); }
    }
    .collapsed .nav-step { display: none; }

    .sidebar-footer { padding: 12px; border-top: 1px solid var(--sb-line); }

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
    .menu-btn { display: none; }
    .spacer { flex: 1; }
    .workspace-chip {
      display: flex; align-items: center; gap: 6px; max-width: 200px;
      height: 36px; padding: 0 12px; border: 1px solid var(--pc-border); border-radius: 999px;
      background: #fff; color: var(--pc-text); font: inherit; font-weight: 500; cursor: pointer;
    }
    .workspace-chip:hover { border-color: var(--pc-primary); color: var(--pc-primary); }
    .workspace-chip mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--pc-primary); }
    .stage-chip {
      display: flex; align-items: center; gap: 6px; max-width: 240px;
      height: 36px; padding: 0 12px; border-radius: 999px; text-decoration: none;
      background: var(--pc-primary-soft); color: var(--pc-primary-600); font-weight: 600; font-size: 13px;
    }
    .stage-chip:hover { background: #e6dfff; }
    .stage-chip.done { background: var(--pc-success-soft); color: #047857; }
    .stage-chip.rejected { background: var(--pc-danger-soft); color: #b91c1c; }
    .stage-chip mat-icon { font-size: 18px; width: 18px; height: 18px; }
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

    @media (max-width: 960px) {
      .menu-btn { display: grid; }
    }
    @media (max-width: 1024px) {
      .workspace-chip .ws-name, .user-meta, .chevron { display: none; }
    }
    @media (max-width: 600px) {
      .workspace-chip, .stage-chip, .divider-v { display: none; }
    }
  `],
})
export class AppComponent {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly breakpoints = inject(BreakpointObserver);
  /** Started here so the stage also refreshes while no action is taken (background stage changes). */
  private readonly workspaceRefresh = inject(WorkspaceRefreshService);

  readonly currentUser$ = this.store.select(selectCurrentUser);
  readonly workspaceName$ = combineLatest([
    this.store.select(selectWorkspaces),
    this.store.select(selectSelectedWorkspaceId),
  ]).pipe(map(([workspaces, id]) => workspaces.find((w) => w.id === id)?.name));

  /** "Step 3 of 7 · Review & Approve" for the workspace in use. */
  readonly stageLabel$ = combineLatest([
    this.store.select(selectWorkspaces),
    this.store.select(selectSelectedWorkspaceId),
  ]).pipe(map(([workspaces, id]) => {
    const ws = workspaces.find((w) => w.id === (id ?? sessionStorage.getItem('workspace_id')));
    if (!ws?.lifecycleStage) return null;
    if (ws.lifecycleStage === 'COMPLETED') return 'Completed';
    if (ws.lifecycleStage === 'REJECTED') return 'Rejected';
    return `Step ${stageIndex(ws.lifecycleStage) + 1} of ${LIFECYCLE_STAGES.length} · ${stageTitle(ws.lifecycleStage)}`;
  }));

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

  constructor() {
    // Stages advance in the background (e.g. when a scan completes), so refresh the
    // workspace summaries on each in-app navigation to keep the stage chip current.
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      filter((e) => !/^\/(login|select-workspace)(\/|\?|$)/.test(e.urlAfterRedirects)),
      filter(() => !!sessionStorage.getItem('workspace_id')),
      takeUntilDestroyed(),
    ).subscribe(() => this.store.dispatch(workspaceActions.loadWorkspaces()));
  }

  /** Lifecycle-ordered sidebar: one item per workspace stage, then post-lifecycle monitoring. */
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
      title: LIFECYCLE_TITLE,
      items: LIFECYCLE_STAGES.map((def) => ({
        label: def.title,
        icon: def.icon,
        route: def.navRoute,
        fragment: def.navFragment,
        stage: def.stage,
        roles: STAGE_ROLES[def.stage],
      })),
    },
    {
      title: 'After Finalize',
      items: [
        { label: 'Monitoring', icon: 'monitor_heart', route: '/monitoring' },
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

  /** Path prefix highlights child pages (/scans/123 → Scan); fragments keep /lifecycle#X distinct. */
  readonly activeOptions = { paths: 'subset', queryParams: 'ignored', fragment: 'exact', matrixParams: 'ignored' } as const;

  private readonly currentStage = toSignal(combineLatest([
    this.store.select(selectWorkspaces),
    this.store.select(selectSelectedWorkspaceId),
  ]).pipe(map(([workspaces, id]) =>
    workspaces.find((w) => w.id === (id ?? sessionStorage.getItem('workspace_id')))?.lifecycleStage)));

  /** Completed lifecycle steps for the workspace in use; null until a stage is known. */
  readonly lifecycleProgress = computed(() => {
    const current = this.currentStage();
    if (!current) return null;
    const total = LIFECYCLE_STAGES.length;
    const done = current === 'COMPLETED' ? total
      : LIFECYCLE_STAGES.filter((d) => this.stepState(d.stage) === 'done').length;
    return { done, total, percent: Math.round((done / total) * 100), rejected: current === 'REJECTED' };
  });

  stepNumber(stage: string): number {
    return stageIndex(stage) + 1;
  }

  stepState(stage: string): 'done' | 'current' | 'upcoming' | 'rejected' | 'halted' {
    const current = this.currentStage();
    if (!current) return 'upcoming';
    const diff = stageIndex(stage) - stageIndex(current);
    if (current === 'REJECTED') return diff < 0 ? 'done' : diff === 0 ? 'rejected' : 'halted';
    return diff < 0 ? 'done' : diff === 0 ? 'current' : 'upcoming';
  }

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
