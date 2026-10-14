export type ApiError = Error & { code?: string; status?: number };

export async function apiRequest<T>(base: string, token: string, path: string, payload?: object, send = fetch): Promise<T> {
  const init: RequestInit = {
    method: payload ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
    headers: { Authorization: `Bearer ${token}`, ...(payload ? { 'Content-Type': 'application/json' } : {}) },
    body: payload ? JSON.stringify(payload) : undefined,
  };
  const url = `${base.replace(/\/$/, '')}${path}`;
  let response = await send(url, init);
  let result = await response.json().catch(() => ({}));
  if (response.status === 401 && result.error?.code === 'COMS_LOGIN_REQUIRED') {
    let refreshed: Response;
    try {
      refreshed = await send('/api/auth/refresh', { method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
    } catch {
      throw Object.assign(new Error('COMS 로그인 상태를 확인하지 못했어요. 잠시 뒤 다시 읽어 주세요.'), { code: 'COMS_AUTH_UNAVAILABLE', status: 503 });
    }
    if (refreshed.status >= 500) throw Object.assign(new Error('COMS 로그인 상태를 확인하지 못했어요. 잠시 뒤 다시 읽어 주세요.'), { code: 'COMS_AUTH_UNAVAILABLE', status: 503 });
    if (refreshed.ok) {
      response = await send(url, init);
      result = await response.json().catch(() => ({}));
    }
  }
  if (!response.ok) {
    throw Object.assign(new Error(result.error?.message || '요청을 처리하지 못했어요.'), { code: result.error?.code, status: response.status }) as ApiError;
  }
  return result as T;
}
