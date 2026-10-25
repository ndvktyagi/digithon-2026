import { Routes } from '@angular/router';

export const CONNECTOR_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./connectors.component').then((m) => m.ConnectorsComponent),
  },
  // New / view / edit now open as tabs on the Connectors page
  { path: 'github', redirectTo: '', pathMatch: 'full' },
  { path: 'postgresql', redirectTo: '', pathMatch: 'full' },
];
