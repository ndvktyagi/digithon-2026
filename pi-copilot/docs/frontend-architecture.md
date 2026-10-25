# PI Security Copilot — Angular 21 Frontend Architecture

## 1. Technology Stack

| Technology | Version | Purpose |
|---|---|---|
| Angular | 21.2.x | Framework (standalone components) |
| Angular Material | 21.x | UI component library |
| NGRX | 21.x | Global state management (Store, Effects, Router) |
| @ngrx/signals | 21.x | Feature-level state management (signalStore) |
| @hey-api/openapi-ts | latest | API client generation from OpenAPI spec |
| Monaco Editor | latest | Code diff viewer |
| chart.js + ng2-charts | latest | Dashboard visualizations |
| date-fns | latest | Date formatting |
| RxJS | 7.x | Reactive programming |
| Keycloak Angular | latest | OIDC integration (optional) |

## 2. Complete Package/Folder Structure

```
frontend/
├── .editorconfig
├── .prettierrc
├── .eslintrc.json
├── angular.json
├── package.json
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.spec.json
├── karma.conf.js
├── proxy.conf.js
│
└── src/
    ├── index.html
    ├── main.ts
    ├── styles.scss
    │
    ├── app/
    │   ├── app.component.ts
    │   ├── app.component.html
    │   ├── app.component.scss
    │   ├── app.config.ts
    │   ├── app.routes.ts
    │   │
    │   ├── core/
    │   │   ├── auth/
    │   │   │   ├── auth.service.ts
    │   │   │   ├── auth.guard.ts
    │   │   │   ├── role.guard.ts
    │   │   │   ├── workspace.guard.ts
    │   │   │   ├── auth.interceptor.ts
    │   │   │   ├── error.interceptor.ts
    │   │   │   ├── correlation.interceptor.ts
    │   │   │   └── auth-state.model.ts
    │   │   ├── config/
    │   │   │   ├── app-config.service.ts
    │   │   │   └── app-config.model.ts
    │   │   ├── notifications/
    │   │   │   ├── notification.service.ts
    │   │   │   └── notification.model.ts
    │   │   ├── error-handling/
    │   │   │   ├── global-error-handler.ts
    │   │   │   └── api-error-mapper.ts
    │   │   └── websocket/
    │   │       ├── ws.service.ts
    │   │       └── ws-types.ts
    │   │
    │   ├── shared/
    │   │   ├── components/
    │   │   │   ├── page-header/
    │   │   │   ├── status-badge/
    │   │   │   ├── confidence-bar/
    │   │   │   ├── confirm-dialog/
    │   │   │   ├── empty-state/
    │   │   │   ├── data-table/
    │   │   │   ├── filter-bar/
    │   │   │   ├── loading-overlay/
    │   │   │   ├── json-viewer/
    │   │   │   ├── diff-viewer/
    │   │   │   └── step-indicator/
    │   │   ├── directives/
    │   │   └── pipes/
    │   │
    │   ├── store/
    │   │   ├── index.ts
    │   │   ├── app.state.ts
    │   │   ├── workspace/
    │   │   ├── auth/
    │   │   ├── feature-flags/
    │   │   └── router/
    │   │
    │   └── features/
    │       ├── login/
    │       ├── dashboard/
    │       ├── connectors/
    │       ├── scan-wizard/
    │       ├── scan-progress/
    │       ├── inventory/
    │       ├── finding-detail/
    │       ├── encryption/
    │       ├── migration/
    │       ├── backfill/
    │       ├── feature-flags/
    │       ├── audit/
    │       └── admin/
    │
    ├── assets/
    │   └── config.json
    └── styles/
        ├── _variables.scss
        ├── _theme.scss
        ├── _typography.scss
        ├── _layout.scss
        └── _utilities.scss
```

## 3. Routing Configuration

```typescript
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component') },
  { path: 'select-workspace', loadComponent: () => import('./features/login/workspace-selector.component'), canActivate: [authGuard] },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', loadComponent: () => import('./features/dashboard/dashboard.component') },
      { path: 'connectors', loadChildren: () => import('./features/connectors/connectors.routes') },
      { path: 'scans/new', loadChildren: () => import('./features/scan-wizard/scan-wizard.routes') },
      { path: 'scans/:scanId', loadComponent: () => import('./features/scan-progress/scan-progress.component') },
      { path: 'inventory', loadComponent: () => import('./features/inventory/inventory.component') },
      { path: 'findings/:findingId', loadComponent: () => import('./features/finding-detail/finding-detail.component') },
      { path: 'encryption', loadComponent: () => import('./features/encryption/encryption.component') },
      { path: 'migrations/:migrationId', loadComponent: () => import('./features/migration/migration-preview.component') },
      { path: 'backfill', loadComponent: () => import('./features/backfill/backfill-monitor.component') },
      { path: 'feature-flags', loadComponent: () => import('./features/feature-flags/feature-flags.component') },
      { path: 'audit', loadComponent: () => import('./features/audit/audit.component') },
      { path: 'admin', loadComponent: () => import('./features/admin/admin.component') },
    ],
  },
];
```

## 4. NGRX State Model

### Global Store Slices
- **AuthState**: isAuthenticated, accessToken, refreshToken, currentUser, tokenExpiry
- **WorkspaceState**: selectedWorkspaceId, workspaces, currentMemberRole
- **FeatureFlagState**: enabled flags, gate statuses

### Feature-level signalStore per feature
Each feature (Dashboard, Connectors, Scan Wizard, Inventory, etc.) uses `@ngrx/signals` `signalStore` for local state management.

## 5. Authentication Flow

1. OIDC Authorization Code Flow with PKCE
2. Access token in memory only, refresh token in sessionStorage
3. AuthInterceptor: attaches Bearer token, handles 401 -> refresh -> retry
4. CorrelationInterceptor: X-Correlation-ID, X-Workspace-Id headers
5. RoleGuard: route.data.roles check against JWT roles claim
6. WorkspaceGuard: ensures workspace selection before accessing project data

## 6. Shared Component Library

| Component | Description |
|---|---|
| page-header | Title, breadcrumbs, action bar |
| status-badge | Colored pill for enum values |
| confidence-bar | HIGH/MEDIUM/LOW visual indicator |
| confirm-dialog | Reusable confirmation dialog |
| empty-state | Centered placeholder for empty data |
| data-table | Reusable mat-table with sort/filter/paginate |
| filter-bar | Search + chip-based filters |
| loading-overlay | Spinner overlay with message |
| json-viewer | Collapsible JSON tree |
| diff-viewer | Monaco-based side-by-side diff |
| step-indicator | Horizontal wizard step indicator |

## 7. Implementation Phases

**Phase 1 (Foundation)**: Project scaffold, Material theming, core services, interceptors, NGRX setup, login/workspace, OpenAPI generation

**Phase 2 (Core Features)**: Dashboard, connectors (GitHub + PostgreSQL), scan wizard, inventory table

**Phase 3 (Detail Views)**: Finding detail, scan progress, encryption setup, migration preview

**Phase 4 (Migration)**: Backfill monitor, feature flags, audit reports, admin

**Phase 5 (Polish)**: Error handling, accessibility, performance, E2E tests