import { HttpInterceptorFn } from '@angular/common/http';

export const correlationInterceptor: HttpInterceptorFn = (req, next) => {
  const correlationId = crypto.randomUUID();
  const workspaceId = sessionStorage.getItem('workspace_id');

  let headers = req.headers.set('X-Correlation-ID', correlationId);
  if (workspaceId) {
    headers = headers.set('X-Workspace-Id', workspaceId);
  }

  return next(req.clone({ headers }));
};