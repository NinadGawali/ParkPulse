import { Route, Routes } from 'react-router-dom';
import { Layout, Protected } from './components/Layout';
import { AccountPage } from './pages/AccountPage';
import { AdminBookingPage, AdminReviewsPage, ModerationPage, NotImplementedPage } from './pages/AdminPages';
import { ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage } from './pages/AuthPages';
import { BookingDetailPage, EarningsPage, HostBookingsPage, MyBookingsPage } from './pages/BookingPages';
import { DevHelperPage, HomePage } from './pages/DevHelperPage';
import { HostListingsPage, ListingManagePage, ListingNewPage } from './pages/HostListingPages';
import { NotificationsPage, ReviewsPage } from './pages/MiscPages';
import { PayoutAccountsPage, ProfilePage, VehiclesPage } from './pages/ProfilePages';
import { ListingDetailPage, SearchPage } from './pages/SearchPages';

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="dev" element={<DevHelperPage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="listings/:id" element={<ListingDetailPage />} />
        <Route path="reviews" element={<ReviewsPage />} />

        <Route path="account" element={<Protected><AccountPage /></Protected>} />
        <Route path="profile" element={<Protected><ProfilePage /></Protected>} />
        <Route path="notifications" element={<Protected><NotificationsPage /></Protected>} />
        <Route path="vehicles" element={<Protected><VehiclesPage /></Protected>} />
        <Route path="bookings" element={<Protected><MyBookingsPage /></Protected>} />
        <Route path="bookings/:id" element={<Protected><BookingDetailPage /></Protected>} />

        <Route path="host/listings" element={<Protected role="HOST"><HostListingsPage /></Protected>} />
        <Route path="host/listings/new" element={<Protected role="HOST"><ListingNewPage /></Protected>} />
        <Route path="host/listings/:id" element={<Protected role="HOST"><ListingManagePage /></Protected>} />
        <Route path="host/bookings" element={<Protected role="HOST"><HostBookingsPage /></Protected>} />
        <Route path="host/earnings" element={<Protected role="HOST"><EarningsPage /></Protected>} />
        <Route path="host/payout-accounts" element={<Protected role="HOST"><PayoutAccountsPage /></Protected>} />

        <Route path="admin/moderation" element={<Protected role="ADMIN"><ModerationPage /></Protected>} />
        <Route path="admin/bookings" element={<Protected role="ADMIN"><AdminBookingPage /></Protected>} />
        <Route path="admin/reviews" element={<Protected role="ADMIN"><AdminReviewsPage /></Protected>} />
        <Route path="admin/not-implemented" element={<Protected role="ADMIN"><NotImplementedPage /></Protected>} />

        <Route path="*" element={<p>Not found.</p>} />
      </Route>
    </Routes>
  );
}
