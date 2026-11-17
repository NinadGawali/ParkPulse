import { lazy } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Layout, Protected } from './components/Layout';
import { HomePage } from './pages/HomePage';
import { ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage, VerifyEmailPage } from './pages/AuthPages';
import { HelpPage, NotFoundPage, NotificationsPage, PrivacyPage, TermsPage } from './pages/MiscPages';

// Heavier areas (maps, host tools, admin) load on demand.
const SearchPage = lazy(() => import('./pages/SearchPage').then((m) => ({ default: m.SearchPage })));
const ListingPage = lazy(() => import('./pages/ListingPage').then((m) => ({ default: m.ListingPage })));
const BookingPages = () => import('./pages/BookingPages');
const MyBookingsPage = lazy(() => BookingPages().then((m) => ({ default: m.MyBookingsPage })));
const BookingPage = lazy(() => BookingPages().then((m) => ({ default: m.BookingPage })));
const HostPages = () => import('./pages/HostPages');
const HostHome = lazy(() => HostPages().then((m) => ({ default: m.HostHome })));
const HostListingsPage = lazy(() => HostPages().then((m) => ({ default: m.HostListingsPage })));
const HostBookingsPage = lazy(() => HostPages().then((m) => ({ default: m.HostBookingsPage })));
const EarningsPage = lazy(() => HostPages().then((m) => ({ default: m.EarningsPage })));
const ManagePages = () => import('./pages/ListingManagePages');
const NewListingPage = lazy(() => ManagePages().then((m) => ({ default: m.NewListingPage })));
const ManageListingPage = lazy(() => ManagePages().then((m) => ({ default: m.ManageListingPage })));
const SettingsPage = lazy(() => import('./pages/SettingsPages').then((m) => ({ default: m.SettingsPage })));
const AdminPages = () => import('./pages/AdminPages');
const ModerationPage = lazy(() => AdminPages().then((m) => ({ default: m.ModerationPage })));
const AdminBookingPage = lazy(() => AdminPages().then((m) => ({ default: m.AdminBookingPage })));
const AdminReviewsPage = lazy(() => AdminPages().then((m) => ({ default: m.AdminReviewsPage })));
const DevPage = import.meta.env.DEV ? lazy(() => import('./pages/DevPage').then((m) => ({ default: m.DevPage }))) : null;

export function App() {
  return (
    <Routes>
      <Route element={<Layout bare />}>
        <Route path="search" element={<SearchPage />} />
      </Route>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="listings/:id" element={<ListingPage />} />
        <Route path="help" element={<HelpPage />} />
        <Route path="terms" element={<TermsPage />} />
        <Route path="privacy" element={<PrivacyPage />} />
        <Route path="host" element={<HostHome />} />

        <Route path="bookings" element={<Protected><MyBookingsPage /></Protected>} />
        <Route path="bookings/:id" element={<Protected><BookingPage /></Protected>} />
        <Route path="notifications" element={<Protected><NotificationsPage /></Protected>} />
        <Route path="settings" element={<Protected><SettingsPage /></Protected>} />
        <Route path="settings/:tab" element={<Protected><SettingsPage /></Protected>} />

        <Route path="host/listings" element={<Protected role="HOST"><HostListingsPage /></Protected>} />
        <Route path="host/listings/new" element={<Protected role="HOST"><NewListingPage /></Protected>} />
        <Route path="host/listings/:id" element={<Protected role="HOST"><ManageListingPage /></Protected>} />
        <Route path="host/bookings" element={<Protected role="HOST"><HostBookingsPage /></Protected>} />
        <Route path="host/earnings" element={<Protected role="HOST"><EarningsPage /></Protected>} />

        <Route path="admin" element={<Protected role="ADMIN"><ModerationPage /></Protected>} />
        <Route path="admin/bookings" element={<Protected role="ADMIN"><AdminBookingPage /></Protected>} />
        <Route path="admin/reviews" element={<Protected role="ADMIN"><AdminReviewsPage /></Protected>} />

        {DevPage && <Route path="dev" element={<DevPage />} />}
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
