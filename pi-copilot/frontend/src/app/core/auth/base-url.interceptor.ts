import { HttpInterceptorFn } from '@angular/common/http';

export const baseUrlInterceptor: HttpInterceptorFn = (req, next) => {
  // If URL is already absolute, don't prefix
  if (req.url.startsWith('http')) {
    return next(req);
  }
  const apiBaseUrl = 'http://localhost:8080/api/v1';
  return next(req.clone({ url: `${apiBaseUrl}${req.url}` }));
};