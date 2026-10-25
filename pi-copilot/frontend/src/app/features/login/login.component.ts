import { AfterViewInit, Component, ElementRef, NgZone, OnInit, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Store } from '@ngrx/store';
import { authActions } from '../../store/auth/auth.actions';
import { selectIsLoading, selectLoginError } from '../../store/auth/auth.selectors';
import { AuthConfig, AuthService } from '../../core/auth/auth.service';

declare const google: any;

const GOOGLE_GSI_SRC = 'https://accounts.google.com/gsi/client';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatDividerModule, MatProgressBarModule,
  ],
  template: `
    <div class="login-container">
      <aside class="brand-panel">
        <div class="brand"><img class="brand-logo" src="brand/pi-logo.png" alt="" /> PI Security Co-Pilot</div>
        <div class="pitch">
          <img class="hero" src="brand/pi-banner.jpg" alt="Personal Identity Security Co-Pilot" />
          <h2>Find, map and protect personal information — before it leaks.</h2>
          <ul>
            <li><mat-icon>radar</mat-icon> Discover PI across code and databases</li>
            <li><mat-icon>fact_check</mat-icon> Review findings with confidence scores</li>
            <li><mat-icon>lock</mat-icon> Encrypt and backfill with safe rollbacks</li>
          </ul>
        </div>
        <p class="foot">Security Co-Pilot · Digithon 2026</p>
      </aside>

      <main class="form-panel">
      <img class="mobile-hero" src="brand/pi-banner.jpg" alt="Personal Identity Security Co-Pilot" />
      <mat-card class="login-card">
        <mat-progress-bar *ngIf="isLoading$ | async" mode="indeterminate"></mat-progress-bar>
        <div class="card-head">
          <img class="brand-logo small" src="brand/pi-logo.png" alt="PI Security Co-Pilot logo" />
          <h1>Welcome back</h1>
          <p>Sign in to PI Security Co-Pilot to continue.</p>
        </div>

        <mat-card-content>
          <div class="error" *ngIf="loginError$ | async as error">
            <mat-icon>error_outline</mat-icon> {{ error }}
          </div>

          <form *ngIf="config?.localLoginEnabled !== false" (ngSubmit)="loginWithPassword()" class="login-form">
            <mat-form-field>
              <mat-label>Username</mat-label>
              <input matInput name="username" [(ngModel)]="username" autocomplete="username" required />
              <mat-icon matSuffix>person</mat-icon>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Password</mat-label>
              <input matInput name="password" [(ngModel)]="password" [type]="hidePassword ? 'password' : 'text'"
                     autocomplete="current-password" required />
              <button mat-icon-button matSuffix type="button" (click)="hidePassword = !hidePassword">
                <mat-icon>{{ hidePassword ? 'visibility_off' : 'visibility' }}</mat-icon>
              </button>
            </mat-form-field>
            <button mat-raised-button color="primary" type="submit" class="full-width submit"
                    [disabled]="!username || !password || (isLoading$ | async)">
              <mat-icon>login</mat-icon> Sign in
            </button>
          </form>

          <div class="divider"><span>or</span></div>

          <div #googleButton class="google-button" [hidden]="!config?.googleClientId"></div>

          <button mat-stroked-button class="full-width" (click)="loginWithSso()">
            <mat-icon>vpn_key</mat-icon> Sign in with SSO (Keycloak)
          </button>
        </mat-card-content>
      </mat-card>
      </main>
    </div>
  `,
  styles: [`
    .login-container {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      min-height: 100vh;
      background: var(--pc-bg);
    }
    .brand-panel {
      position: relative; overflow: hidden;
      display: flex; flex-direction: column; justify-content: space-between;
      padding: 40px 48px;
      /* navy tones sampled from the brand banner so the artwork blends into the panel */
      background:
        radial-gradient(circle at 50% 35%, rgba(34, 211, 238, 0.18), transparent 55%),
        linear-gradient(160deg, #061a3a 0%, #04122b 55%, #020b1c 100%);
      color: #fff;
    }
    .brand { display: flex; align-items: center; gap: 10px; font-size: 18px; font-weight: 700; }
    .brand-logo { width: 40px; height: 40px; border-radius: 10px; object-fit: cover; }
    .brand-logo.small {
      display: block; width: 52px; height: 52px; margin-bottom: 16px;
      box-shadow: 0 4px 14px rgba(14, 116, 200, 0.35);
    }
    .hero {
      display: block; width: 100%; max-width: 620px; margin-bottom: 32px;
      border-radius: var(--pc-radius);
      border: 1px solid rgba(103, 232, 249, 0.25);
      box-shadow: 0 0 40px rgba(34, 211, 238, 0.25), 0 20px 40px rgba(0, 0, 0, 0.4);
    }
    .mobile-hero { display: none; }
    .pitch h2 { max-width: 560px; font-size: 26px; line-height: 1.3; font-weight: 700; color: #fff; }
    .pitch ul { list-style: none; margin-top: 28px; display: flex; flex-direction: column; gap: 14px; }
    .pitch li { display: flex; align-items: center; gap: 12px; font-size: 15px; opacity: 0.95; }
    .pitch li mat-icon {
      display: grid; place-items: center; width: 34px; height: 34px; padding: 7px; box-sizing: border-box;
      font-size: 20px; border-radius: 10px; color: #67e8f9; background: rgba(34, 211, 238, 0.14);
    }
    .foot { font-size: 12px; opacity: 0.7; }

    .form-panel {
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20px;
      padding: 24px 16px;
    }
    .login-card { width: 100%; max-width: 420px; padding: 12px 8px; }
    .card-head { padding: 20px 20px 0; }
    .card-head h1 { font-size: 24px; }
    .card-head p { margin-top: 4px; color: var(--pc-text-muted); }
    mat-card-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-top: 24px !important;
    }
    .login-form {
      display: flex;
      flex-direction: column;
    }
    .full-width {
      width: 100%;
      height: 44px;
    }
    .error {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      border-radius: var(--pc-radius-sm);
      background: var(--pc-danger-soft);
      color: #b91c1c;
    }
    .divider {
      display: flex;
      align-items: center;
      gap: 12px;
      color: var(--pc-text-subtle);
      font-size: 12px;
      text-transform: uppercase;
    }
    .divider::before, .divider::after {
      content: '';
      flex: 1;
      border-top: 1px solid var(--pc-border);
    }
    .google-button {
      display: flex;
      justify-content: center;
      min-height: 44px;
    }
    .google-button[hidden] {
      display: none;
    }
    @media (max-width: 900px) {
      .login-container { grid-template-columns: 1fr; }
      .brand-panel { display: none; }
      .mobile-hero {
        display: block; width: 100%; max-width: 420px; border-radius: var(--pc-radius);
        box-shadow: 0 8px 24px rgba(4, 18, 43, 0.25);
      }
    }
  `],
})
export class LoginComponent implements OnInit, AfterViewInit {
  private readonly store = inject(Store);
  private readonly authService = inject(AuthService);
  private readonly zone = inject(NgZone);

  @ViewChild('googleButton') googleButton?: ElementRef<HTMLDivElement>;

  readonly isLoading$ = this.store.select(selectIsLoading);
  readonly loginError$ = this.store.select(selectLoginError);

  config: AuthConfig | null = null;
  username = '';
  password = '';
  hidePassword = true;
  private viewReady = false;

  ngOnInit(): void {
    this.authService.getAuthConfig().subscribe({
      next: (config) => {
        this.config = config;
        this.initGoogle();
      },
      error: () => (this.config = { localLoginEnabled: true, googleClientId: '' }),
    });
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.initGoogle();
  }

  loginWithPassword(): void {
    this.store.dispatch(authActions.passwordLogin({ username: this.username, password: this.password }));
  }

  loginWithSso(): void {
    this.store.dispatch(authActions.loginRedirect());
  }

  private initGoogle(): void {
    const clientId = this.config?.googleClientId;
    if (!clientId || !this.viewReady || !this.googleButton) {
      return;
    }
    this.loadGoogleScript().then(() => {
      google.accounts.id.initialize({
        client_id: clientId,
        callback: (response: { credential: string }) =>
          this.zone.run(() =>
            this.store.dispatch(authActions.googleLogin({ credential: response.credential }))
          ),
      });
      google.accounts.id.renderButton(this.googleButton!.nativeElement, {
        theme: 'outline',
        size: 'large',
        text: 'signin_with',
        width: 392,
      });
    });
  }

  private loadGoogleScript(): Promise<void> {
    if (typeof google !== 'undefined' && google.accounts?.id) {
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = GOOGLE_GSI_SRC;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Failed to load Google Sign-In'));
      document.head.appendChild(script);
    });
  }
}
