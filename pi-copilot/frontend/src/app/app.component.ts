import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { NavigationEnd, RouterModule, Router } from '@angular/router';
import { filter, map, startWith } from 'rxjs/operators';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
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

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, RouterModule, MatToolbarModule, MatSidenavModule,
    MatListModule, MatIconModule, MatButtonModule, MatMenuModule, MatDividerModule,
  ],
  template: `
    <router-outlet *ngIf="!showShell()"></router-outlet>
    <mat-sidenav-container *ngIf="showShell()" class="sidenav-container">
      <mat-sidenav #sidenav mode="side" [opened]="true" class="sidenav">
        <div class="sidenav-header">
          <mat-icon class="logo-icon">security</mat-icon>
          <span class="logo-text">PI Copilot</span>
        </div>
        <mat-divider></mat-divider>
        <mat-nav-list>
          <a mat-list-item *ngFor="let item of visibleNavItems$ | async" [routerLink]="item.route"
             routerLinkActive="active-link">
            <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
            <span matListItemTitle>{{ item.label }}</span>
          </a>
        </mat-nav-list>
      </mat-sidenav>

      <mat-sidenav-content>
        <mat-toolbar color="primary" class="toolbar">
          <span class="toolbar-spacer"></span>
          <button mat-icon-button [matMenuTriggerFor]="userMenu">
            <mat-icon>account_circle</mat-icon>
          </button>
          <mat-menu #userMenu="matMenu">
            <button mat-menu-item disabled>
              <span>{{ (currentUser$ | async)?.name || 'User' }}</span>
            </button>
            <mat-divider></mat-divider>
            <button mat-menu-item routerLink="/select-workspace">
              <mat-icon>swap_horiz</mat-icon> Switch Workspace
            </button>
            <button mat-menu-item (click)="logout()">
              <mat-icon>logout</mat-icon> Sign Out
            </button>
          </mat-menu>
        </mat-toolbar>

        <main class="main-content">
          <router-outlet></router-outlet>
        </main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: [`
    .sidenav-container { height: 100vh; }
    .sidenav { width: 240px; background: #fafafa; border-right: 1px solid #e0e0e0; }
    .sidenav-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
    }
    .logo-icon { font-size: 28px; width: 28px; height: 28px; color: #1565c0; }
    .logo-text { font-size: 18px; font-weight: 600; color: #1565c0; }
    .active-link { background: #e3f2fd !important; color: #1565c0; }
    .toolbar { position: sticky; top: 0; z-index: 10; }
    .toolbar-spacer { flex: 1 1 auto; }
    .main-content { min-height: calc(100vh - 64px); background: #f5f5f5; }
  `],
})
export class AppComponent {
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  readonly currentUser$ = this.store.select(selectCurrentUser);

  /** Login and workspace selection render full-screen, without the nav shell. */
  readonly showShell = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => !/^\/(login|select-workspace)(\/|\?|$)/.test(e.urlAfterRedirects)),
      startWith(false),
    ),
    { initialValue: false },
  );

  navItems: NavItem[] = [
    { label: 'Dashboard', icon: 'dashboard', route: '/dashboard' },
    { label: 'Connectors', icon: 'link', route: '/connectors', roles: ['APP_OWNER', 'APP_DEVELOPER', 'PLATFORM_ADMIN'] },
    { label: 'New Scan', icon: 'radar', route: '/scans/new' },
    { label: 'Inventory', icon: 'inventory_2', route: '/inventory' },
    { label: 'Encryption', icon: 'vpn_key', route: '/encryption', roles: ['PLATFORM_ADMIN', 'SECURITY_REVIEWER'] },
    { label: 'Backfill', icon: 'sync', route: '/backfill' },
    { label: 'Feature Flags', icon: 'toggle_on', route: '/feature-flags', roles: ['PLATFORM_ADMIN'] },
    { label: 'Audit', icon: 'history', route: '/audit', roles: ['PRIVACY_REVIEWER', 'SECURITY_REVIEWER', 'AUDITOR', 'PLATFORM_ADMIN'] },
    { label: 'Admin', icon: 'admin_panel_settings', route: '/admin', roles: ['PLATFORM_ADMIN'] },
  ];

  readonly visibleNavItems$ = this.store.select(selectUserRoles).pipe(
    map((roles) => this.navItems.filter(
      (item) => !item.roles || item.roles.some((r) => roles.includes(r as never))
    )),
  );

  logout(): void {
    this.store.dispatch(authActions.logout());
  }
}