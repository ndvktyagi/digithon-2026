import { Routes } from '@angular/router';

export const SCAN_WIZARD_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./scan-wizard.component').then((m) => m.ScanWizardComponent),
  },
];