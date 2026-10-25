import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Store } from '@ngrx/store';
import { authActions } from '../../store/auth/auth.actions';

/** Landing page for the Keycloak redirect; exchanges the code for tokens. */
@Component({
  selector: 'app-login-callback',
  standalone: true,
  imports: [CommonModule, MatProgressSpinnerModule],
  template: `
    <div class="callback-container">
      <mat-spinner diameter="48"></mat-spinner>
      <p>Completing sign-in…</p>
    </div>
  `,
  styles: [`
    .callback-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 16px;
      min-height: 100vh;
      color: #616161;
    }
  `],
})
export class LoginCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly store = inject(Store);

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const code = params.get('code');
    const state = params.get('state');
    if (!code || !state) {
      this.store.dispatch(authActions.loginFailure({
        error: params.get('error_description') || 'Sign-in was cancelled.',
      }));
      this.router.navigate(['/login']);
      return;
    }
    this.store.dispatch(authActions.loginCallback({ code, state }));
  }
}
