import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CurrentUser, TokenSet } from './auth-state.model';

export interface AuthConfig {
  localLoginEnabled: boolean;
  googleClientId: string;
}

const PKCE_VERIFIER_KEY = 'pkce_verifier';
const OIDC_STATE_KEY = 'oidc_state';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly oidcConfig = {
    issuer: 'http://localhost:8180/realms/picopilot',
    clientId: 'pi-copilot-web',
    redirectUri: `${window.location.origin}/login/callback`,
    scope: 'openid profile email roles',
  };

  getAuthConfig(): Observable<AuthConfig> {
    return this.http.get<AuthConfig>('/auth/config');
  }

  /** Redirects to Keycloak using the authorization code flow with PKCE. */
  async startLogin(): Promise<void> {
    const verifier = this.randomString(64);
    const state = crypto.randomUUID();
    sessionStorage.setItem(PKCE_VERIFIER_KEY, verifier);
    sessionStorage.setItem(OIDC_STATE_KEY, state);

    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    const params = new URLSearchParams({
      response_type: 'code',
      client_id: this.oidcConfig.clientId,
      redirect_uri: this.oidcConfig.redirectUri,
      scope: this.oidcConfig.scope,
      state,
      code_challenge: this.base64Url(new Uint8Array(digest)),
      code_challenge_method: 'S256',
    });
    window.location.href = `${this.oidcConfig.issuer}/protocol/openid-connect/auth?${params}`;
  }

  exchangeCode(code: string, state: string): Observable<{ tokens: TokenSet; user: CurrentUser }> {
    const verifier = sessionStorage.getItem(PKCE_VERIFIER_KEY);
    const expectedState = sessionStorage.getItem(OIDC_STATE_KEY);
    sessionStorage.removeItem(PKCE_VERIFIER_KEY);
    sessionStorage.removeItem(OIDC_STATE_KEY);
    if (!verifier || state !== expectedState) {
      return throwError(() => new Error('Invalid login state. Please sign in again.'));
    }

    const body = new HttpParams()
      .set('grant_type', 'authorization_code')
      .set('client_id', this.oidcConfig.clientId)
      .set('code', code)
      .set('redirect_uri', this.oidcConfig.redirectUri)
      .set('code_verifier', verifier);
    return this.http.post<any>(
      `${this.oidcConfig.issuer}/protocol/openid-connect/token`,
      body.toString(),
      { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
    ).pipe(
      map((response) => this.toSession(response)),
      catchError((error: HttpErrorResponse) =>
        throwError(() => new Error('Authentication failed: ' + error.message))
      )
    );
  }

  loginWithPassword(username: string, password: string): Observable<{ tokens: TokenSet; user: CurrentUser }> {
    return this.http.post<any>('/auth/login', { username, password }).pipe(
      map((response) => this.toSession(response)),
      catchError((error: HttpErrorResponse) =>
        throwError(() => new Error(
          error.status === 401 ? 'Invalid username or password.' : 'Sign-in failed. Is the backend running?'
        ))
      )
    );
  }

  loginWithGoogle(credential: string): Observable<{ tokens: TokenSet; user: CurrentUser }> {
    return this.http.post<any>('/auth/google', { credential }).pipe(
      map((response) => this.toSession(response)),
      catchError((error: HttpErrorResponse) =>
        throwError(() => new Error(error.error?.message || 'Google sign-in failed.'))
      )
    );
  }

  refreshToken(refreshToken: string): Observable<{ accessToken: string; expiresAt: number }> {
    return this.http.post<any>('/auth/refresh', {
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
    }).pipe(
      map((response) => ({
        accessToken: response.access_token,
        expiresAt: Date.now() + response.expires_in * 1000,
      })),
      catchError(() => {
        this.clearSession();
        return throwError(() => new Error('Token refresh failed'));
      })
    );
  }

  getAccessToken(): string | null {
    const token = sessionStorage.getItem('access_token');
    if (token && this.isExpired(token)) {
      this.clearSession();
      return null;
    }
    return token;
  }

  getRefreshToken(): string | null {
    return sessionStorage.getItem('refresh_token');
  }

  setTokens(tokens: TokenSet): void {
    sessionStorage.setItem('access_token', tokens.accessToken);
    if (tokens.refreshToken) {
      sessionStorage.setItem('refresh_token', tokens.refreshToken);
    }
  }

  /** Rebuilds the session from storage so a page reload keeps the user signed in. */
  restoreSession(): { tokens: TokenSet; user: CurrentUser } | null {
    const accessToken = this.getAccessToken();
    if (!accessToken) {
      return null;
    }
    return {
      tokens: {
        accessToken,
        refreshToken: this.getRefreshToken() ?? '',
        idToken: '',
        expiresAt: (this.decodePayload(accessToken)?.exp ?? 0) * 1000,
      },
      user: this.parseUserFromToken(accessToken),
    };
  }

  clearSession(): void {
    sessionStorage.removeItem('access_token');
    sessionStorage.removeItem('refresh_token');
    sessionStorage.removeItem('workspace_id');
  }

  private toSession(response: any): { tokens: TokenSet; user: CurrentUser } {
    return {
      tokens: {
        accessToken: response.access_token,
        refreshToken: response.refresh_token ?? '',
        idToken: response.id_token ?? '',
        expiresAt: Date.now() + response.expires_in * 1000,
      },
      user: this.parseUserFromToken(response.access_token),
    };
  }

  private parseUserFromToken(token: string): CurrentUser {
    const payload = this.decodePayload(token);
    if (!payload) {
      return { sub: '', email: '', name: '', preferredUsername: '', roles: [], workspaces: [] };
    }
    return {
      sub: payload.sub,
      email: payload.email || '',
      name: payload.name || payload.preferred_username || '',
      preferredUsername: payload.preferred_username || '',
      roles: payload.roles || payload.realm_access?.roles || [],
      workspaces: payload.workspaces || [],
    };
  }

  private decodePayload(token: string): any | null {
    try {
      const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(base64));
    } catch {
      return null;
    }
  }

  private isExpired(token: string): boolean {
    const exp = this.decodePayload(token)?.exp;
    return typeof exp === 'number' && exp * 1000 <= Date.now();
  }

  private randomString(length: number): string {
    return this.base64Url(crypto.getRandomValues(new Uint8Array(length))).slice(0, length);
  }

  private base64Url(bytes: Uint8Array): string {
    return btoa(String.fromCharCode(...bytes))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  }
}
