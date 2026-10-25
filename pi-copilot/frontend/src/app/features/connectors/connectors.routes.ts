import { Routes } from '@angular/router';

export const CONNECTOR_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./connectors.component').then((m) => m.ConnectorsComponent),
  },
  {
    path: 'github',
    loadComponent: () =>
      import('./github/github-connector.component').then(
        (m) => m.GithubConnectorComponent
      ),
  },
  {
    path: 'postgresql',
    loadComponent: () =>
      import('./postgresql/postgresql-connector.component').then(
        (m) => m.PostgresqlConnectorComponent
      ),
  },
];