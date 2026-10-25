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
    path: '',
    canActivate: [authGuard, workspaceGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then(
            (m) => m.DashboardComponent
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
        path: 'scans/new',
        loadChildren: () =>
          import('./features/scan-wizard/scan-wizard.routes').then(
            (m) => m.SCAN_WIZARD_ROUTES
          ),
        canActivate: [roleGuard],
        data: { roles: [UserRole.APP_OWNER, UserRole.APP_DEVELOPER] },
      },
      {
        path: 'scans/:scanId',
        loadComponent: () =>
          import('./features/scan-progress/scan-progress.component').then(
            (m) => m.ScanProgressComponent
          ),
      },
      {
        path: 'inventory',
        loadComponent: () =>
          import('./features/inventory/inventory.component').then(
            (m) => m.InventoryComponent
          ),
      },
      {
        path: 'findings/:findingId',
        loadComponent: () =>
          import('./features/finding-detail/finding-detail.component').then(
            (m) => m.FindingDetailComponent
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
      {
        path: 'feature-flags',
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