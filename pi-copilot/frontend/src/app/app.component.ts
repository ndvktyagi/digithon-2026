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
  /** Accent for the icon tile, hover and active states. */
  color: string;
}

/** Same role restrictions as the stage pages' route guards. */
const STAGE_ROLES: Partial<Record<string, string[]>> = {
  CONNECT: ['APP_OWNER', 'APP_DEVELOPER', 'PLATFORM_ADMIN'],
  ENCRYPTION_SETUP: ['PLATFORM_ADMIN', 'SECURITY_REVIEWER'],
  ENABLE_ENCRYPTION: ['PLATFORM_ADMIN'],
};

const LIFECYCLE_TITLE = 'Workspace Lifecycle';

/** One accent per lifecycle step, in step order. */
const STAGE_COLORS = ['#3b82f6', '#06b6d4', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899', '#14b8a6', '#6366f1'];

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
            <div class="progress" *ngIf="section.title === lifecycleTitle && lifecycleProgress() as p">
              <div class="progress-meta"><span>{{ p.done }} of {{ p.total }} complete</span><span>{{ p.percent }}%</span></div>
              <div class="progress-track"><div class="progress-fill" [style.width.%]="p.percent"></div></div>
            </div>
            <a *ngFor="let item of section.items" class="nav-link" [routerLink]="item.route" [fragment]="item.fragment"
               routerLinkActive="active" [routerLinkActiveOptions]="activeOptions" (click)="onNavigate()"
               [style.--nav-c]="item.color"
               [matTooltip]="item.label" matTooltipPosition="right"
               [matTooltipDisabled]="!collapsed() || isMobile()">
              <span class="nav-icon"><mat-icon>{{ item.icon }}</mat-icon></span>
              <span class="nav-label">{{ item.label }}</span>
              <span class="nav-step" *ngIf="item.stage" [ngClass]="stepState(item.stage)"
                    [attr.aria-label]="'Step ' + stepNumber(item.stage) + ', ' + stepState(item.stage)">
                <mat-icon *ngIf="stepState(item.stage) === 'done'">check</mat-icon>
                <mat-icon *ngIf="stepState(item.stage) === 'rejected'">block</mat-icon>
                <ng-container *ngIf="stepState(item.stage) !== 'done' && stepState(item.stage) !== 'rejected'">{{ stepNumber(item.stage) }}</ng-container>
              </span>
            </a>
          </ng-container>
        </nav>

        <div class="sidebar-footer">
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
    .collapsed .progress { display: none; }
    .collapsed .brand { justify-content: center; padding: 20px 0; }
    .collapsed .brand-logo { display: none; }
    .collapsed .nav-link { justify-content: center; padding: 8px 0; }
    .collapsed .nav-link:hover { transform: none; }

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
      --nav-c: var(--pc-primary);
      position: relative;
      display: flex; align-items: center; gap: 12px;
      padding: 7px 10px; margin: 3px 0;
      border-radius: 12px;
      color: #4b5563; text-decoration: none; font-weight: 500; cursor: pointer;
      white-space: nowrap;
      transition: background 0.2s, color 0.2s, transform 0.2s ease;
    }
    .nav-link:hover {
      background: color-mix(in srgb, var(--nav-c) 8%, transparent);
      color: var(--pc-text);
      transform: translateX(3px);
    }
    .nav-link.active {
      background: linear-gradient(90deg, color-mix(in srgb, var(--nav-c) 16%, transparent), color-mix(in srgb, var(--nav-c) 4%, transparent));
      color: color-mix(in srgb, var(--nav-c) 75%, #111827); font-weight: 600;
    }
    /* Accent bar on the active item */
    .nav-link.active::before {
      content: ''; position: absolute; left: -12px; top: 8px; bottom: 8px; width: 4px;
      border-radius: 0 4px 4px 0; background: var(--nav-c);
    }
    .nav-icon {
      display: grid; place-items: center; width: 32px; height: 32px; flex-shrink: 0;
      border-radius: 9px;
      background: color-mix(in srgb, var(--nav-c) 12%, transparent);
      color: var(--nav-c);
      transition: background 0.2s, color 0.2s, transform 0.25s ease, box-shadow 0.2s;
    }
    .nav-link:hover .nav-icon { transform: scale(1.08) rotate(-4deg); }
    .nav-link.active .nav-icon {
      background: linear-gradient(135deg, var(--nav-c), color-mix(in srgb, var(--nav-c) 70%, #000));
      color: #fff;
      box-shadow: 0 4px 10px color-mix(in srgb, var(--nav-c) 40%, transparent);
    }
    .nav-link.logout { --nav-c: var(--pc-danger); color: var(--pc-danger); font-weight: 600; }
    .nav-link.logout:hover { background: var(--pc-danger-soft); color: #b91c1c; }
    .nav-link.logout:hover .nav-icon { background: var(--pc-danger); color: #fff; }

    .progress { padding: 2px 12px 8px; }
    .progress-meta {
      display: flex; justify-content: space-between;
      font-size: 11px; color: var(--pc-text-muted); margin-bottom: 5px;
    }
    .progress-track { height: 6px; border-radius: 999px; background: #eef0f4; overflow: hidden; }
    .progress-fill {
      height: 100%; border-radius: inherit;
      background: linear-gradient(90deg, #10b981, #06b6d4, #5b3df5);
      transition: width 0.4s ease;
    }
    .nav-icon mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .nav-label { flex: 1; }
    .nav-step {
      display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; flex-shrink: 0;
      border: 1.5px solid var(--pc-border); color: var(--pc-text-subtle); font-size: 11px; font-weight: 700;
    }
    .nav-step mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .nav-step.done { background: var(--pc-success); border-color: var(--pc-success); color: #fff; }
    .nav-step.current { border-color: var(--pc-primary); background: var(--pc-primary); color: #fff; box-shadow: 0 0 0 3px var(--pc-primary-soft); }
    .nav-step.rejected { border-color: var(--pc-danger); background: var(--pc-danger); color: #fff; box-shadow: 0 0 0 3px var(--pc-danger-soft); }
    /* Steps after a rejection will never run */
    .nav-step.halted { opacity: 0.45; border-style: dashed; }
    .collapsed .nav-step { display: none; }

    .sidebar-footer { padding: 12px; border-top: 1px solid var(--pc-border); }

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
        { label: 'Dashboard', icon: 'grid_view', route: '/dashboard', color: '#6366f1' },
        { label: 'Workspaces', icon: 'workspaces', route: '/workspaces', color: '#0ea5e9' },
        { label: 'Lifecycle', icon: 'route', route: '/lifecycle', color: '#8b5cf6' },
      ],
    },
    {
      title: LIFECYCLE_TITLE,
      items: LIFECYCLE_STAGES.map((def, i) => ({
        label: def.title,
        icon: def.icon,
        route: def.navRoute,
        fragment: def.navFragment,
        stage: def.stage,
        roles: STAGE_ROLES[def.stage],
        color: STAGE_COLORS[i % STAGE_COLORS.length],
      })),
    },
    {
      title: 'After Finalize',
      items: [
        { label: 'Monitoring', icon: 'monitor_heart', route: '/monitoring', color: '#14b8a6' },
      ],
    },
    {
      title: 'Governance',
      items: [
        { label: 'Audit', icon: 'history', route: '/audit', color: '#64748b', roles: ['PRIVACY_REVIEWER', 'SECURITY_REVIEWER', 'AUDITOR', 'PLATFORM_ADMIN'] },
        { label: 'Admin', icon: 'admin_panel_settings', route: '/admin', color: '#7c3aed', roles: ['PLATFORM_ADMIN'] },
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

  readonly lifecycleTitle = LIFECYCLE_TITLE;

  /** Completed lifecycle steps for the workspace in use; null until a stage is known. */
  readonly lifecycleProgress = computed(() => {
    const current = this.currentStage();
    if (!current) return null;
    const total = LIFECYCLE_STAGES.length;
    const done = current === 'COMPLETED' ? total
      : LIFECYCLE_STAGES.filter((d) => this.stepState(d.stage) === 'done').length;
    return { done, total, percent: Math.round((done / total) * 100) };
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
