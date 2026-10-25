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
      <mat-card class="login-card">
        <mat-progress-bar *ngIf="isLoading$ | async" mode="indeterminate"></mat-progress-bar>
        <mat-card-header>
          <mat-card-title>
            <mat-icon class="logo-icon">security</mat-icon>
            PI Security Copilot
          </mat-card-title>
          <mat-card-subtitle>
            Discover, map, and protect personal information in your applications
          </mat-card-subtitle>
        </mat-card-header>

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
            <button mat-raised-button color="primary" type="submit" class="full-width"
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
    </div>
  `,
  styles: [`
    .login-container {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      padding: 16px;
      background: linear-gradient(135deg, #1565c0 0%, #0d47a1 100%);
    }
    .login-card {
      width: 100%;
      max-width: 440px;
      padding: 24px;
    }
    .logo-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
      margin-right: 8px;
      vertical-align: middle;
    }
    mat-card-subtitle {
      margin-top: 8px;
    }
    mat-card-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-top: 24px;
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
      border-radius: 4px;
      background: #ffebee;
      color: #c62828;
    }
    .divider {
      display: flex;
      align-items: center;
      gap: 12px;
      color: #9e9e9e;
      font-size: 12px;
      text-transform: uppercase;
    }
    .divider::before, .divider::after {
      content: '';
      flex: 1;
      border-top: 1px solid #e0e0e0;
    }
    .google-button {
      display: flex;
      justify-content: center;
      min-height: 44px;
    }
    .google-button[hidden] {
      display: none;
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
