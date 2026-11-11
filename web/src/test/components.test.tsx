import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { ProblemView } from '../components/ui';
import { Layout, Protected } from '../components/Layout';
import { DebugPanel } from '../components/DebugPanel';
import { pushDebug } from '../state/debug';

vi.mock('../state/auth', () => {
  const state = {
    loggedIn: true,
    roles: ['DRIVER'],
    account: { email: 'd@example.com' },
    accountId: 'a1',
    hasRole: (r: string) => r === 'DRIVER',
    logout: async () => undefined,
  };
  return { useAuth: () => state };
});
vi.mock('../api/endpoints', () => ({ notifications: { unread: async () => ({ unread: 3 }) } }));

describe('ProblemView', () => {
  it('shows status, title, detail, type and field errors of an RFC 7807 problem', () => {
    const err = new ApiError({
      type: 'https://parksmart.dev/problems/validation-failed',
      title: 'Validation failed',
      status: 400,
      detail: 'Request is invalid',
      correlationId: 'corr-9',
      errors: [{ field: 'pricePerHour', message: 'out of range' }],
    });
    render(<ProblemView error={err} />);
    expect(screen.getByRole('alert')).toHaveTextContent('400');
    expect(screen.getByText('Validation failed')).toBeInTheDocument();
    expect(screen.getByText('Request is invalid')).toBeInTheDocument();
    expect(screen.getByText('https://parksmart.dev/problems/validation-failed')).toBeInTheDocument();
    expect(screen.getByText('corr-9')).toBeInTheDocument();
    expect(screen.getByText('pricePerHour')).toBeInTheDocument();
  });

  it('adds a rate-limit hint on 429 and renders nothing without an error', () => {
    const { container, rerender } = render(<ProblemView error={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ProblemView error={new ApiError({ type: 'about:blank', title: 'Too Many Requests', status: 429 })} />);
    expect(screen.getByText(/dev:direct-auth/)).toBeInTheDocument();
  });
});

describe('role-aware menu', () => {
  it('shows driver items but not host or admin items for a DRIVER, and the unread badge', async () => {
    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>,
    );
    expect(screen.getByText('My bookings')).toBeInTheDocument();
    expect(screen.queryByText('New listing')).not.toBeInTheDocument();
    expect(screen.queryByText('Moderation')).not.toBeInTheDocument();
    expect(await screen.findByText('3')).toBeInTheDocument();
  });

  it('blocks a page that needs HOST with an explanation', () => {
    render(
      <MemoryRouter>
        <Protected role="HOST">
          <p>secret host page</p>
        </Protected>
      </MemoryRouter>,
    );
    expect(screen.queryByText('secret host page')).not.toBeInTheDocument();
    expect(screen.getByText(/needs the HOST role/)).toBeInTheDocument();
  });
});

describe('DebugPanel', () => {
  it('is collapsed by default and shows the last request and response when opened', async () => {
    pushDebug({
      id: 1,
      at: '2026-11-11T10:00:00Z',
      method: 'POST',
      url: '/api/v1/bookings',
      requestHeaders: { 'Idempotency-Key': 'ps-123' },
      requestBody: { listingId: 'l1' },
      status: 409,
      responseHeaders: {},
      responseBody: { title: 'Booking overlap' },
      ms: 12,
    });
    render(<DebugPanel />);
    expect(screen.queryByText(/Idempotency-Key/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Last request/ }));
    expect(screen.getByText(/Idempotency-Key/)).toBeInTheDocument();
    expect(screen.getByText(/Booking overlap/)).toBeInTheDocument();
  });
});
