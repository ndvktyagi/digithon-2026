export enum UserRole {
  PLATFORM_ADMIN = 'PLATFORM_ADMIN',
  APP_OWNER = 'APP_OWNER',
  APP_DEVELOPER = 'APP_DEVELOPER',
  PRIVACY_REVIEWER = 'PRIVACY_REVIEWER',
  SECURITY_REVIEWER = 'SECURITY_REVIEWER',
  AUDITOR = 'AUDITOR',
}

export interface CurrentUser {
  sub: string;
  email: string;
  name: string;
  preferredUsername: string;
  roles: UserRole[];
  workspaces: WorkspaceSummary[];
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
}

export interface TokenSet {
  accessToken: string;
  refreshToken: string;
  idToken: string;
  expiresAt: number;
}

export interface LoginCredentials {
  username: string;
  password: string;
}
