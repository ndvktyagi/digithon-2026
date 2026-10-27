import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';
import { workspaceGuard } from './core/auth/workspace.guard';
import { UserRole } from './core/auth/auth-state.model';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'login/callback',
    loadComponent: () =>
      import('./features/login/login-callback.component').then((m) => m.LoginCallbackComponent),
  },
  {
    path: 'select-workspace',
    loadComponent: () =>
      import('./features/login/workspace-selector.component').then(
        (m) => m.WorkspaceSelectorComponent
      ),
    canActivate: [authGuard],
  },
  {
    // Same workspace manager, rendered inside the app shell once a workspace is in use.
    path: 'workspaces',
    loadComponent: () =>
      import('./features/login/workspace-selector.component').then(
        (m) => m.WorkspaceSelectorComponent
      ),
    canActivate: [authGuard],
    data: { embedded: true },
  },
  {
    // Landing page after login; renders inside the app shell with or without a workspace.
    path: 'dashboard',
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
    canActivate: [authGuard],
  },
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: '',
    canActivate: [authGuard, workspaceGuard],
    children: [
      {
        path: 'lifecycle',
        loadComponent: () =>
          import('./features/lifecycle/workspace-lifecycle.component').then(
            (m) => m.WorkspaceLifecycleComponent
          ),
      },
      {
        path: 'connectors',
        loadChildren: () =>
          import('./features/connectors/connectors.routes').then(
            (m) => m.CONNECTOR_ROUTES
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.APP_OWNER, UserRole.APP_DEVELOPER, UserRole.PLATFORM_ADMIN] },
      },
      {
        path: 'scans',
        loadComponent: () =>
          import('./features/scans/scan-list.component').then(
            (m) => m.ScanListComponent
          ),
      },
      {
        path: 'review',
        loadComponent: () =>
          import('./features/scans/scan-list.component').then(
            (m) => m.ScanListComponent
          ),
        data: { mode: 'review' },
      },
      {
        path: 'implementation',
        loadComponent: () =>
          import('./features/implementation/implementation.component').then(
            (m) => m.ImplementationComponent
          ),
      },
      {
        path: 'final-review',
        loadComponent: () =>
          import('./features/final-review/final-review.component').then(
            (m) => m.FinalReviewComponent
          ),
      },
      {
        path: 'data-sync',
        loadComponent: () =>
          import('./features/data-sync/data-sync.component').then(
            (m) => m.DataSyncComponent
          ),
      },
      {
        path: 'monitoring',
        loadComponent: () =>
          import('./features/monitoring/monitoring.component').then(
            (m) => m.MonitoringComponent
          ),
      },
      {
        path: 'scans/new',
        loadComponent: () =>
          import('./features/scans/new-scan.component').then(
            (m) => m.NewScanComponent
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.APP_OWNER, UserRole.APP_DEVELOPER, UserRole.PLATFORM_ADMIN] },
      },
      {
        path: 'scans/:scanId',
        loadComponent: () =>
          import('./features/scans/scan-detail.component').then(
            (m) => m.ScanDetailComponent
          ),
      },
      {
        path: 'encryption',
        loadComponent: () =>
          import('./features/encryption/encryption.component').then(
            (m) => m.EncryptionComponent
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.PLATFORM_ADMIN, UserRole.SECURITY_REVIEWER] },
      },
      {
        path: 'migrations/:migrationId',
        loadComponent: () =>
          import('./features/migration/migration-preview.component').then(
            (m) => m.MigrationPreviewComponent
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.APP_OWNER, UserRole.APP_DEVELOPER, UserRole.SECURITY_REVIEWER] },
      },
      {
        path: 'backfill',
        loadComponent: () =>
          import('./features/backfill/backfill-monitor.component').then(
            (m) => m.BackfillMonitorComponent
          ),
      },
      { path: 'feature-flags', redirectTo: 'enable-encryption', pathMatch: 'full' },
      {
        path: 'enable-encryption',
        loadComponent: () =>
          import('./features/feature-flags/feature-flags.component').then(
            (m) => m.FeatureFlagsComponent
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.PLATFORM_ADMIN] },
      },
      {
        path: 'audit',
        loadComponent: () =>
          import('./features/audit/audit.component').then(
            (m) => m.AuditComponent
          ),
        canActivate: [roleGuard],
        data: {
          roles: [
            UserRole.PRIVACY_REVIEWER,
            UserRole.SECURITY_REVIEWER,
            UserRole.AUDITOR,
            UserRole.PLATFORM_ADMIN,
          ],
        },
      },
      {
        path: 'admin',
        loadComponent: () =>
          import('./features/admin/admin.component').then(
            (m) => m.AdminComponent
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.PLATFORM_ADMIN] },
      },
    ],
  },
  { path: '**', redirectTo: 'dashboard' },
];