import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { ProblemView, StatusBadge } from '../components/ui';
import { Layout, Protected } from '../components/Layout';
import { DebugPanel } from '../components/DebugPanel';
import { pushDebug } from '../state/debug';

vi.mock('../state/auth', () => {
  const state = {
    ready: true,
    loggedIn: true,
    roles: ['DRIVER'],
    account: { email: 'd@example.com' },
    accountId: 'a1',
    hasRole: (r: string) => r === 'DRIVER',
    logout: async () => undefined,
  };
  return { useAuth: () => state };
});
vi.mock('../api/endpoints', () => ({
  notifications: { unread: async () => ({ unread: 3 }), list: async () => ({ items: [] }) },
  profile: { publicOf: async () => ({ accountId: 'a1', displayName: 'Dee Driver', ratingCount: 0 }) },
  listings: { get: async () => ({ data: null }) },
}));

describe('ProblemView', () => {
  it('shows the title, detail, field errors and a support reference of an RFC 7807 problem', () => {
    const err = new ApiError({
      type: 'https://parksmart.dev/problems/validation-failed',
      title: 'Validation failed',
      status: 400,
      detail: 'Request is invalid',
      correlationId: 'corr-9',
      errors: [{ field: 'pricePerHour', message: 'out of range' }],
    });
    render(<ProblemView error={err} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('Validation failed')).toBeInTheDocument();
    expect(screen.getByText('Request is invalid')).toBeInTheDocument();
    expect(screen.getByText('pricePerHour')).toBeInTheDocument();
    expect(screen.getByText(/Reference: corr-9/)).toBeInTheDocument();
  });

  it('turns rate limits and network failures into friendly messages, and renders nothing without an error', () => {
    const { container, rerender } = render(<ProblemView error={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ProblemView error={new ApiError({ type: 'about:blank', title: 'Too Many Requests', status: 429 })} />);
    expect(screen.getByText('Too many attempts')).toBeInTheDocument();
    rerender(<ProblemView error={new ApiError({ type: 'about:blank', title: 'Network error', status: 0, detail: 'Failed to fetch.' })} />);
    expect(screen.getByText("Can't reach ParkSmart")).toBeInTheDocument();
  });
});

describe('StatusBadge', () => {
  it('uses human labels for booking and listing states', () => {
    render(
      <>
        <StatusBadge kind="booking" value="AWAITING_HOST_APPROVAL" />
        <StatusBadge kind="listing" value="PUBLISHED" />
      </>,
    );
    expect(screen.getByText('Awaiting host approval')).toBeInTheDocument();
    expect(screen.getByText('Live')).toBeInTheDocument();
  });
});

describe('header', () => {
  it('shows driver navigation, the unread badge and the user menu, but no admin link for a DRIVER', async () => {
    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>,
    );
    expect(screen.getAllByText('My bookings').length).toBeGreaterThan(0);
    expect(screen.getAllByText('List your space').length).toBeGreaterThan(0);
    expect(screen.queryByText('Admin')).not.toBeInTheDocument();
    expect(await screen.findByText('3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Dee Driver|Account/ }));
    expect(screen.getByText('Log out')).toBeInTheDocument();
  });
});

describe('Protected', () => {
  it('sends a non-host to the become-a-host page', () => {
    render(
      <MemoryRouter initialEntries={['/host/listings']}>
        <Routes>
          <Route
            path="/host/listings"
            element={
              <Protected role="HOST">
                <p>secret host page</p>
              </Protected>
            }
          />
          <Route path="/host" element={<p>become a host</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.queryByText('secret host page')).not.toBeInTheDocument();
    expect(screen.getByText('become a host')).toBeInTheDocument();
  });

  it('blocks an admin page for a non-admin', () => {
    render(
      <MemoryRouter>
        <Protected role="ADMIN">
          <p>admin only</p>
        </Protected>
      </MemoryRouter>,
    );
    expect(screen.queryByText('admin only')).not.toBeInTheDocument();
    expect(screen.getByText(/don't have access/)).toBeInTheDocument();
  });
});

describe('DebugPanel (dev server only)', () => {
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
    await userEvent.click(screen.getByRole('button', { name: /last request/ }));
    expect(screen.getByText(/Idempotency-Key/)).toBeInTheDocument();
    expect(screen.getByText(/Booking overlap/)).toBeInTheDocument();
  });
});
